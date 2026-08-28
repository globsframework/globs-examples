package org.globsframework.sample.rest;

import org.apache.hc.core5.http2.config.H2Config;
import org.apache.hc.core5.http2.impl.nio.bootstrap.H2ServerBootstrap;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.globsframework.commandline.ParseCommandLine;
import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.annotations.AutoIncrement;
import org.globsframework.core.metamodel.annotations.DefaultInteger;
import org.globsframework.core.metamodel.annotations.DefaultString;
import org.globsframework.core.metamodel.annotations.KeyField;
import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.streams.accessors.IntegerAccessor;
import org.globsframework.http.GlobHttpContent;
import org.globsframework.http.HttpServerRegister;
import org.globsframework.http.server.apache.GlobHttpApacheBuilder;
import org.globsframework.http.server.apache.Server;
import org.globsframework.sql.*;
import org.globsframework.sql.annotations.DbTableName;
import org.globsframework.sql.constraints.Constraints;
import org.globsframework.sql.drivers.jdbc.JdbcSqlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/*
start with following argument

 --dbUrl jdbc:hsqldb:file:./db/ --user sa --password ""


Expose api route /student en post
Expose un route /api/openapi
create a table
insert in the table and return the newly student.

 */

public class Example1 {

    private static final Logger log = LoggerFactory.getLogger(Example1.class);

    public static void main(String[] args) throws InterruptedException {
        Glob argument = ParseCommandLine.parse(ArgumentType.TYPE, args);
        SqlService sqlService = new JdbcSqlService(argument.getNotEmpty(ArgumentType.dbUrl),
                argument.getNotEmpty(ArgumentType.user),
                argument.get(ArgumentType.password));

        {
            SqlConnection db = sqlService.getDb();
            db.createTable(StudentType.TYPE);
            db.commitAndClose();
        }

        final HttpServerRegister httpServerRegister = new HttpServerRegister("EstablishmentServer/0.1");

        final byte[] bytes = "some data".getBytes(StandardCharsets.UTF_8);

        httpServerRegister.register("/api/greeting", null)
                .get(GreetingType.TYPE, null, (body, url, queryParameters, headerType) -> {
//                    String message = String.format("Hello %s!!!",
//                            queryParameters.getOrDefault(GreetingType.name, (String) GreetingType.name.getDefaultValue()));
//                    int sleep = queryParameters.getOpt(GreetingType.sleep).orElse((Integer) GreetingType.sleep.getDefaultValue());
//                    if (sleep > 0) {
//                        Thread.sleep(sleep);
//                    }
                    return CompletableFuture.completedFuture(GlobHttpContent.TYPE.instantiate()
                            .set(GlobHttpContent.content, bytes));
                });
//                .withExecutor(Executors.newVirtualThreadPerTaskExecutor());

        httpServerRegister.register("/student", null)
                .post(StudentType.TYPE, null, (body, url, queryParameters) -> {

                    // insert ths student in db with a direct mapping.
                    SqlConnection db = sqlService.getDb();
                    CreateBuilder createBuilder = db.getCreateBuilder(StudentType.TYPE);

                    body.getOptNotEmpty(StudentType.firstName).ifPresent(v -> createBuilder.set(StudentType.firstName, v));
                    body.getOptNotEmpty(StudentType.lastName).ifPresent(v -> createBuilder.set(StudentType.lastName, v));

                    IntegerAccessor keyGeneratedAccessor = createBuilder.getKeyGeneratedAccessor(StudentType.id);
                    int id;
                    try (SqlRequest insertRequest = createBuilder.getRequest()) {
                        insertRequest.apply();
                        id = keyGeneratedAccessor.getInteger();
                    }
                    db.commit();

                    // query the db with the created student
                    Glob createdData;
                    try (SelectQuery query = db.getQueryBuilder(StudentType.TYPE, Constraints.equal(StudentType.id, id))
                            .selectAll()
                            .getQuery()) {
                        createdData = query.executeUnique();
                    } finally {
                        db.commitAndClose();
                    }
                    return CompletableFuture.completedFuture(createdData);
                })
                .declareReturnType(StudentType.TYPE);

        H2ServerBootstrap h2ServerBootstrap = H2ServerBootstrap.bootstrap()
                .setH2Config(H2Config.DEFAULT)
                .setIOReactorConfig(IOReactorConfig.custom().setSoReuseAddress(true).build());

        GlobHttpApacheBuilder globHttpApacheBuilder = new GlobHttpApacheBuilder(httpServerRegister);
        final Server server =
                globHttpApacheBuilder.startAndWaitForStartup(h2ServerBootstrap,
                        argument.get(ArgumentType.port, 3100));

        System.out.println("Listen on port: " + server.getPort());
        synchronized (Example1.class) {
            Example1.class.wait();
        }
    }

    public static class StudentType {
        public static final GlobType TYPE;

        public static final IntegerField id;

        public static final StringField firstName;

        public static final StringField lastName;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("Student");
            typeBuilder.addAnnotation(DbTableName.create("students"));
            id = typeBuilder.declareIntegerField("id", KeyField.ZERO, AutoIncrement.INSTANCE);
            firstName = typeBuilder.declareStringField("firstName");
            lastName = typeBuilder.declareStringField("lastName");
            TYPE = typeBuilder.build();
        }
    }
/*
    public static class Argument {

        public String dbUrl;

        public String user;

        public String password;

        public int port;
    }
    Argument arguments = new Argument();
    ParseResult parseResult = new CommandLine(arguments).parseArgs(args);
*/
    // CommandLine interpreter that uses reflection to initialize an annotated user object
    // with values obtained from the command line arguments.

    public static class ArgumentType {
        public static final GlobType TYPE;

        public static final StringField dbUrl;

        public static final StringField user;

        public static final StringField password;

        public static final IntegerField port;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("Argument");
            dbUrl = typeBuilder.declareStringField("dbUrl");
            user = typeBuilder.declareStringField("user");
            password = typeBuilder.declareStringField("password");
            port = typeBuilder.declareIntegerField("port");
            TYPE = typeBuilder.build();
        }
    }

    public static class GreetingType {
        public static final GlobType TYPE;

        public static final StringField name;

        public static final IntegerField sleep;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("Greeting");
            name = typeBuilder.declareStringField("name", DefaultString.create("World"));
            sleep = typeBuilder.declareIntegerField("sleep", DefaultInteger.TYPE.instantiate().set(DefaultInteger.VALUE, 0));
            TYPE = typeBuilder.build();
        }
    }
}
