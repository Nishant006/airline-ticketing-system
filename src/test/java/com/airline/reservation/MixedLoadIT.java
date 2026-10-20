package com.airline.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MixedLoadIT extends IntegrationSupport {
  private static final int BASELINE_CLIENTS = 16;
  private static final long SEARCH_TARGET_NANOS = Duration.ofSeconds(1).toNanos();

  @LocalServerPort private int port;

  @Autowired
  @Qualifier("readDataSource")
  private HikariDataSource readPool;

  @Autowired
  @Qualifier("writeDataSource")
  private HikariDataSource writePool;

  @Test
  void measures25To1TrafficWithIsolatedPoolsAndPreservesOwnership() throws Exception {
    PerformanceDataset.seed(jdbc, TOMORROW);
    List<String> report = new ArrayList<>();
    report.add("500 schedules, 100 route matches, 30 dates, 600,000 synthetic allocations.");
    report.add("Local PostgreSQL Docker; one app/client JVM; closed loop, no think time.");
    report.add("25 searches then one disjoint single-seat booking; 3 cycles/client; warm cache.");
    report.add(
        "Baseline: "
            + BASELINE_CLIENTS
            + " clients, every search 200 and under one second. Stress: 64/192 clients, overload reported separately.");
    report.add(
        "Read/write pool maxima: "
            + readPool.getMaximumPoolSize()
            + "/"
            + writePool.getMaximumPoolSize()
            + ". Short local samples, not sustained/open-loop production capacity certification.");
    AtomicInteger targets = new AtomicInteger();
    try (HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
      for (int index = 0; index < 20; index++) {
        assertThat(search(client, TOMORROW).status()).isEqualTo(200);
      }
      for (int clients : List.of(BASELINE_CLIENTS, 64, 192)) {
        report.add(measurePhase(client, clients, targets));
      }
      report.add(hotSeat(client));
    }
    assertThat(
            jdbc.sql(
                    """
                                        SELECT count(*) FROM (
                                          SELECT flight_instance_id, seat_label FROM booking_seat WHERE status = 'BOOKED'
                                          GROUP BY flight_instance_id, seat_label HAVING count(*) > 1
                                        ) duplicates
                                        """)
                .query(Integer.class)
                .single())
        .isZero();
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM booking_seat WHERE seat_label = '1B' AND status = 'BOOKED'")
                .query(Integer.class)
                .single())
        .isEqualTo(targets.get());
    report.add(
        "No duplicate active seats; all "
            + targets.get()
            + " disjoint bookings own their intended seat.");
    String evidence = String.join("\n", report) + "\n";
    Files.writeString(Path.of("target/mixed-load.txt"), evidence);
    System.out.println(evidence);
  }

  private String measurePhase(HttpClient client, int clients, AtomicInteger targets)
      throws Exception {
    CountDownLatch ready = new CountDownLatch(clients);
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Replies>> workers = new ArrayList<>();
    for (int index = 0; index < clients; index++) {
      workers.add(
          () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
              throw new IllegalStateException("Mixed-load start timed out");
            }
            List<Reply> searches = new ArrayList<>();
            List<Reply> bookings = new ArrayList<>();
            for (int cycle = 0; cycle < 3; cycle++) {
              int target = targets.getAndIncrement();
              LocalDate date = TOMORROW.plusDays(target / 500);
              for (int request = 0; request < 25; request++) {
                searches.add(search(client, date));
              }
              bookings.add(book(client, target % 500 + 1, date, "1B"));
            }
            return new Replies(searches, bookings);
          });
    }
    List<Reply> searches = new ArrayList<>();
    List<Reply> bookings = new ArrayList<>();
    long began;
    try (ExecutorService executor = Executors.newFixedThreadPool(clients)) {
      List<Future<Replies>> futures = new ArrayList<>();
      for (Callable<Replies> worker : workers) {
        futures.add(executor.submit(worker));
      }
      boolean allReady = ready.await(10, TimeUnit.SECONDS);
      began = System.nanoTime();
      start.countDown();
      assertThat(allReady).isTrue();
      for (Future<Replies> future : futures) {
        Replies replies = future.get(60, TimeUnit.SECONDS);
        searches.addAll(replies.searches());
        bookings.addAll(replies.bookings());
      }
    }
    double seconds = (System.nanoTime() - began) / 1_000_000_000.0;
    assertThat(searches).allMatch(reply -> reply.status() == 200 || reply.status() == 503);
    assertThat(bookings).allMatch(reply -> reply.status() == 201);
    List<Reply> successfulSearches =
        searches.stream().filter(reply -> reply.status() == 200).toList();
    assertThat(successfulSearches).as("Successful searches at %s clients", clients).isNotEmpty();
    long unavailable = searches.stream().filter(reply -> reply.status() == 503).count();
    if (clients == BASELINE_CLIENTS) {
      assertThat(unavailable).as("Baseline overload responses").isZero();
      assertThat(searches)
          .as("Every baseline search must finish in under one second")
          .allMatch(reply -> reply.nanos() < SEARCH_TARGET_NANOS);
    }
    return String.format(
        Locale.ROOT,
        "mode=%s clients=%d searches=%d [200=%d/503=%d] bookings=%d [all201] elapsed=%.3fs attemptedRps=%.1f "
            + "searchAll[p50/p95/p99/max]=%s search200[p50/p95/p99/max]=%s "
            + "booking[p50/p95/p99/max]=%s searchOver1s=%d",
        clients == BASELINE_CLIENTS ? "baseline" : "stress",
        clients,
        searches.size(),
        successfulSearches.size(),
        unavailable,
        bookings.size(),
        seconds,
        (searches.size() + bookings.size()) / seconds,
        percentiles(searches),
        percentiles(successfulSearches),
        percentiles(bookings),
        searches.stream().filter(reply -> reply.nanos() >= SEARCH_TARGET_NANOS).count());
  }

  private String hotSeat(HttpClient client) throws Exception {
    List<Reply> replies =
        concurrently(
            64,
            ignored -> {
              try {
                return book(client, 1, TOMORROW, "1C");
              } catch (Exception exception) {
                throw new IllegalStateException("Hot-seat HTTP request failed", exception);
              }
            });
    assertThat(replies.stream().filter(reply -> reply.status() == 201).count()).isEqualTo(1);
    assertThat(replies.stream().filter(reply -> reply.status() == 409).count()).isEqualTo(63);
    return "Hot seat: 64 contenders, one201/63x409; latency[p50/p95/p99/max]="
        + percentiles(replies);
  }

  private Reply search(HttpClient client, LocalDate date) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:"
                        + port
                        + "/api/v1/flights?originCode=DXB&destinationCode=LHR&date="
                        + date))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
    long began = System.nanoTime();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    long elapsed = System.nanoTime() - began;
    if (response.statusCode() == 200) {
      assertThat(response.body().split("\"flightNumber\"", -1)).hasSize(101);
    } else if (response.statusCode() == 503) {
      assertThat(response.body()).contains("\"errorCode\":\"SERVICE_UNAVAILABLE\"");
      assertThat(response.headers().firstValue("Retry-After")).contains("1");
    }
    return new Reply(response.statusCode(), elapsed);
  }

  private Reply book(HttpClient client, int schedule, LocalDate date, String seat)
      throws Exception {
    String body =
        """
                        {"flightNumber":"PF%04d","flightDate":"%s","passengers":[{"name":"Load fixture","seat":"%s"}]}
                        """
            .formatted(schedule, date, seat);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/bookings"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    long began = System.nanoTime();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    return new Reply(response.statusCode(), System.nanoTime() - began);
  }

  private String percentiles(List<Reply> replies) {
    List<Long> times = replies.stream().map(Reply::nanos).sorted().toList();
    return String.format(
        Locale.ROOT,
        "%.3f/%.3f/%.3f/%.3f ms",
        times.get((int) Math.ceil(times.size() * 0.50) - 1) / 1_000_000.0,
        times.get((int) Math.ceil(times.size() * 0.95) - 1) / 1_000_000.0,
        times.get((int) Math.ceil(times.size() * 0.99) - 1) / 1_000_000.0,
        times.getLast() / 1_000_000.0);
  }

  private record Reply(int status, long nanos) {}

  private record Replies(List<Reply> searches, List<Reply> bookings) {}
}
