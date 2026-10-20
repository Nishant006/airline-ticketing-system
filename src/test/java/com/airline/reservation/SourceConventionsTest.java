package com.airline.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SourceConventionsTest {
  private static final Pattern WILDCARD_IMPORT = Pattern.compile("(?m)^import\\s+.*\\.\\*;");
  private static final Pattern INFERRED_LOCAL_TYPE = Pattern.compile("\\bvar\\s+\\w+\\s*[=:]");

  @Test
  void javaSourcesUseExplicitImportsAndLocalTypes() throws IOException {
    for (Path root : List.of(Path.of("src/main/java"), Path.of("src/test/java"))) {
      try (Stream<Path> paths = Files.walk(root)) {
        for (Path path : paths.filter(file -> file.toString().endsWith(".java")).toList()) {
          String source = Files.readString(path);
          assertThat(WILDCARD_IMPORT.matcher(source).find())
              .as("Wildcard import in %s", path)
              .isFalse();
          assertThat(INFERRED_LOCAL_TYPE.matcher(source).find())
              .as("Inferred type in %s", path)
              .isFalse();
        }
      }
    }
  }
}
