package com.airline.reservation.config;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({DataSourceProperties.class, ReadDataSourceProperties.class})
public class DataSourceConfiguration {
  @Bean(destroyMethod = "close")
  @ConfigurationProperties("spring.datasource.hikari")
  HikariDataSource writeDataSource(
      DataSourceProperties properties, ObjectProvider<JdbcConnectionDetails> connectionDetails) {
    return createPool(properties, connectionDetails);
  }

  @Bean(destroyMethod = "close")
  HikariDataSource readDataSource(
      DataSourceProperties properties,
      ObjectProvider<JdbcConnectionDetails> connectionDetails,
      ReadDataSourceProperties read) {
    HikariDataSource pool = createPool(properties, connectionDetails);
    pool.setPoolName("airline-read-pool");
    pool.setMaximumPoolSize(read.maximumPoolSize());
    pool.setMinimumIdle(1);
    pool.setConnectionTimeout(read.connectionTimeoutMs());
    pool.setReadOnly(true);
    pool.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
    pool.setConnectionInitSql(
        "SET TIME ZONE 'UTC'; SET statement_timeout = "
            + read.statementTimeoutMs()
            + "; SET lock_timeout = "
            + read.lockTimeoutMs());
    return pool;
  }

  @Bean
  @Primary
  DataSource dataSource(
      @Qualifier("writeDataSource") HikariDataSource write,
      @Qualifier("readDataSource") HikariDataSource read) {
    LazyConnectionDataSourceProxy dataSource = new LazyConnectionDataSourceProxy(write);
    dataSource.setReadOnlyDataSource(read);
    dataSource.setDefaultAutoCommit(true);
    dataSource.setDefaultTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    return dataSource;
  }

  private HikariDataSource createPool(
      DataSourceProperties properties, ObjectProvider<JdbcConnectionDetails> connectionDetails) {
    JdbcConnectionDetails details = connectionDetails.getIfAvailable();
    if (details == null) {
      return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }
    return DataSourceBuilder.create()
        .type(HikariDataSource.class)
        .driverClassName(details.getDriverClassName())
        .url(details.getJdbcUrl())
        .username(details.getUsername())
        .password(details.getPassword())
        .build();
  }
}
