package dev.smithyai.orchestrator.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageConfiguration {

    @Bean
    public DataSource dataSource(StorageConfig storage) {
        var config = new HikariConfig();
        config.setJdbcUrl(
            "jdbc:sqlite:" + storage.resolvedDatabase() + "?journal_mode=WAL&busy_timeout=5000&foreign_keys=on"
        );
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(4);
        config.setPoolName("run-store");
        return new HikariDataSource(config);
    }
}
