package com.airline.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.jpa.properties.hibernate.generate_statistics=true",
      "spring.jpa.properties.hibernate.session.events.log=false",
      "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.airline.reservation.SearchPerformanceIT$SearchSqlInspector"
    })
class SearchPerformanceIT extends IntegrationSupport {
  @LocalServerPort private int port;
  @Autowired private DataSource dataSource;

  @Test
  void measuresHttpLatencyAndActualQueryPlanAtTheClarifiedScheduleScale() throws Exception {
    PerformanceDataset.seed(jdbc, TOMORROW);
    URI uri =
        URI.create(
            "http://localhost:"
                + port
                + "/api/v1/flights?originCode=DXB&destinationCode=LHR&date="
                + TOMORROW);
    try (HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
      for (int index = 0; index < 10; index++) {
        search(client, uri);
      }
      statistics().clear();
      List<Long> elapsed = new ArrayList<>();
      for (int index = 0; index < 100; index++) {
        elapsed.add(search(client, uri));
      }
      assertThat(statistics().getPrepareStatementCount()).isEqualTo(100);
      assertThat(statistics().getEntityLoadCount()).isZero();
      Collections.sort(elapsed);
      statistics().clear();
      List<Long> concurrentElapsed = concurrentSearches(client, uri);
      assertThat(statistics().getPrepareStatementCount()).isEqualTo(200);
      assertThat(statistics().getEntityLoadCount()).isZero();
      Collections.sort(concurrentElapsed);
      String query = SearchSqlInspector.QUERY.get();
      assertThat(query).isNotBlank();
      String plan = explain(query);
      String evidence =
          """
                            Dataset: 500 daily schedules, 100 matching DXB/LHR; 30 dates per schedule;
                            15,000 instances, 30,000 bookings, 300,000 active and 300,000 released seats.
                            Synthetic volume is an explicit measurement assumption, not an interviewer requirement.
                            Load: local HTTP, one application, 10 warmups; 100 sequential then 200 at 8 workers.
                            Acceptance: every measured search returns 200 in under one second; percentiles are diagnostic.
                            OS: %s %s; Java %s; processors visible: %d
                            Sequential HTTP milliseconds: p50=%.3f p95=%.3f max=%.3f
                            Eight-worker HTTP milliseconds: p50=%.3f p95=%.3f max=%.3f
                            Statements per request: 1; entity loads: 0
                            SQL: %s
                            EXPLAIN (ANALYZE, BUFFERS):
                            %s
                            """
              .formatted(
                  System.getProperty("os.name"),
                  System.getProperty("os.arch"),
                  System.getProperty("java.version"),
                  Runtime.getRuntime().availableProcessors(),
                  millis(elapsed.get(49)),
                  millis(elapsed.get(94)),
                  millis(elapsed.getLast()),
                  millis(concurrentElapsed.get(99)),
                  millis(concurrentElapsed.get(189)),
                  millis(concurrentElapsed.getLast()),
                  query,
                  plan);
      Files.writeString(Path.of("target/search-performance.txt"), evidence);
      System.out.println(evidence);
      assertThat(millis(elapsed.getLast())).as("Slowest sequential search").isLessThan(1000);
      assertThat(millis(concurrentElapsed.getLast()))
          .as("Slowest eight-worker search")
          .isLessThan(1000);
    }
  }

  private List<Long> concurrentSearches(HttpClient client, URI uri) throws Exception {
    List<Callable<List<Long>>> workers = new ArrayList<>();
    for (int worker = 0; worker < 8; worker++) {
      workers.add(
          () -> {
            List<Long> elapsed = new ArrayList<>();
            for (int request = 0; request < 25; request++) {
              elapsed.add(search(client, uri));
            }
            return elapsed;
          });
    }
    try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
      List<Long> elapsed = new ArrayList<>();
      for (Future<List<Long>> future : executor.invokeAll(workers)) {
        elapsed.addAll(future.get());
      }
      return elapsed;
    }
  }

  private long search(HttpClient client, URI uri) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
    long started = System.nanoTime();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    long elapsed = System.nanoTime() - started;
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"availableSeats\":160");
    assertThat(response.body().split("\"flightNumber\"", -1)).hasSize(101);
    return elapsed;
  }

  private String explain(String query) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("EXPLAIN (ANALYZE, BUFFERS) " + query)) {
      statement.setObject(1, TOMORROW);
      statement.setString(2, "DXB");
      statement.setString(3, "LHR");
      statement.setShort(4, (short) TOMORROW.getDayOfWeek().getValue());
      try (ResultSet rows = statement.executeQuery()) {
        List<String> lines = new ArrayList<>();
        while (rows.next()) {
          lines.add(rows.getString(1));
        }
        return String.join("\n", lines);
      }
    }
  }

  private double millis(long nanos) {
    return nanos / 1_000_000.0;
  }

  public static class SearchSqlInspector implements StatementInspector {
    static final AtomicReference<String> QUERY = new AtomicReference<>();

    @Override
    public String inspect(String sql) {
      String normalized = sql.toLowerCase(Locale.ROOT);
      if (normalized.startsWith("select")
          && normalized.contains("count(")
          && normalized.contains("booking_seat")) {
        QUERY.set(sql);
      }
      return sql;
    }
  }
}
