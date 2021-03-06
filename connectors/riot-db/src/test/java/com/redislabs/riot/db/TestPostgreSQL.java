package com.redislabs.riot.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.redislabs.riot.DataType;
import com.redislabs.testcontainers.RedisServer;
import io.lettuce.core.api.sync.RedisHashCommands;
import io.lettuce.core.api.sync.RedisKeyCommands;
import io.lettuce.core.api.sync.RedisServerCommands;
import io.lettuce.core.api.sync.RedisStringCommands;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Testcontainers
@SuppressWarnings({"rawtypes", "unchecked"})
public class TestPostgreSQL extends AbstractDatabaseTest {

    @Container
    private static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer(DockerImageName.parse(PostgreSQLContainer.IMAGE).withTag(PostgreSQLContainer.DEFAULT_TAG));
    private static DataSource dataSource;
    private static Connection connection;

    @BeforeAll
    public static void setupAll() throws SQLException, IOException {
        dataSource = dataSource(POSTGRESQL);
        connection = dataSource.getConnection();
        ScriptRunner scriptRunner = ScriptRunner.builder().connection(connection).autoCommit(false).stopOnError(true).build();
        InputStream inputStream = TestPostgreSQL.class.getClassLoader().getResourceAsStream("northwind.sql");
        scriptRunner.runScript(new InputStreamReader(inputStream));
    }

    @AfterAll
    public static void teardownAll() throws SQLException {
        connection.close();
    }

    @AfterEach
    public void clearTables() throws SQLException {
        Statement statement = connection.createStatement();
        statement.execute("DROP TABLE IF EXISTS mytable");

    }

    @ParameterizedTest
    @MethodSource("containers")
    public void testExport(RedisServer container) throws Exception {
        Statement statement = connection.createStatement();
        statement.execute("CREATE TABLE mytable (id smallint NOT NULL, field1 bpchar, field2 bpchar)");
        statement.execute("ALTER TABLE ONLY mytable ADD CONSTRAINT pk_mytable PRIMARY KEY (id)");
        dataGenerator(container).dataTypes(DataType.HASH).build().call();
        execute("export-postgresql", container, r -> configureExportCommand(r, POSTGRESQL));
        statement.execute("SELECT COUNT(*) AS count FROM mytable");
        ResultSet countResultSet = statement.getResultSet();
        countResultSet.next();
        statement.execute("SELECT * from mytable ORDER BY id ASC");
        ResultSet resultSet = statement.getResultSet();
        int index = 0;
        while (resultSet.next()) {
            Assertions.assertEquals(index, resultSet.getInt("id"));
            index++;
        }
        RedisServerCommands<String, String> sync = sync(container);
        Assertions.assertEquals(sync.dbsize().longValue(), index);
    }

    @ParameterizedTest
    @MethodSource("containers")
    public void testImport(RedisServer container) throws Exception {
        execute("import-postgresql", container, r -> configureImportCommand(r, POSTGRESQL));
        Statement statement = connection.createStatement();
        statement.execute("SELECT COUNT(*) AS count FROM orders");
        RedisKeyCommands<String, String> sync = sync(container);
        List<String> keys = sync.keys("order:*");
        ResultSet resultSet = statement.getResultSet();
        resultSet.next();
        Assertions.assertEquals(resultSet.getLong("count"), keys.size());
        Map<String, String> order = ((RedisHashCommands<String, String>) sync).hgetall("order:10248");
        Assertions.assertEquals("10248", order.get("order_id"));
        Assertions.assertEquals("VINET", order.get("customer_id"));
    }

    @ParameterizedTest
    @MethodSource("containers")
    public void testImportSet(RedisServer container) throws Exception {
        execute("import-postgresql-set", container, r -> configureImportCommand(r, POSTGRESQL));
        Statement statement = connection.createStatement();
        statement.execute("SELECT * FROM orders");
        ResultSet resultSet = statement.getResultSet();
        long count = 0;
        while (resultSet.next()) {
            int orderId = resultSet.getInt("order_id");
            RedisStringCommands<String, String> sync = sync(container);
            String order = sync.get("order:" + orderId);
            ObjectMapper mapper = new ObjectMapper();
            ObjectReader reader = mapper.readerFor(Map.class);
            Map<String, Object> orderMap = reader.readValue(order);
            Assertions.assertEquals(orderId, orderMap.get("order_id"));
            Assertions.assertEquals(resultSet.getString("customer_id"), orderMap.get("customer_id"));
            Assertions.assertEquals(resultSet.getInt("employee_id"), orderMap.get("employee_id"));
            Assertions.assertEquals(resultSet.getDate("order_date"), java.sql.Date.valueOf(LocalDate.from(DateTimeFormatter.ISO_DATE.parse((String) orderMap.get("order_date")))));
            Assertions.assertEquals(resultSet.getFloat("freight"), ((Double) orderMap.get("freight")).floatValue(), 0);
            count++;
        }
        RedisKeyCommands<String, String> sync = sync(container);
        List<String> keys = sync.keys("order:*");
        Assertions.assertEquals(count, keys.size());
    }

}
