package org.globsframework.sample.rest;

import org.apache.hc.core5.http.impl.bootstrap.AsyncServerBootstrap;
import org.globsframework.commandline.ParseCommandLine;
import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.annotations.AutoIncrement;
import org.globsframework.core.metamodel.annotations.AutoIncrement_;
import org.globsframework.core.metamodel.annotations.KeyField;
import org.globsframework.core.metamodel.annotations.KeyField_;
import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.streams.accessors.IntegerAccessor;
import org.globsframework.http.HttpServerRegister;
import org.globsframework.sql.*;
import org.globsframework.sql.annotations.DbTableName;
import org.globsframework.sql.annotations.DbTableName_;
import org.globsframework.sql.constraints.Constraints;
import org.globsframework.sql.drivers.jdbc.JdbcSqlService;

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

    public static void main(String[] args) {
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
                        insertRequest.run();
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
        HttpServerRegister.Server server = httpServerRegister.startAndWaitForStartup(
                AsyncServerBootstrap.bootstrap(), argument.get(ArgumentType.port, 3000));
        System.out.println("Listen on port: " + server.getPort());
    }

    public static class StudentType {
        @DbTableName_("students")
        public static final GlobType TYPE;

        @KeyField_
        @AutoIncrement_
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
}
