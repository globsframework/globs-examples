package org.globsframework.sample.graphql;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http2.config.H2Config;
import org.apache.hc.core5.http2.impl.nio.bootstrap.H2ServerBootstrap;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.globsframework.commandline.ParseCommandLine;
import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.annotations.DefaultString;
import org.globsframework.core.metamodel.annotations.KeyField;
import org.globsframework.core.metamodel.fields.*;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.core.streams.GlobStream;
import org.globsframework.core.streams.accessors.LongAccessor;
import org.globsframework.core.utils.Strings;
import org.globsframework.graphql.GQLGlobCaller;
import org.globsframework.graphql.GQLGlobCallerBuilder;
import org.globsframework.graphql.GlobSchemaGenerator;
import org.globsframework.graphql.OnLoad;
import org.globsframework.graphql.db.ConnectionBuilder;
import org.globsframework.graphql.model.GQLPageInfo;
import org.globsframework.graphql.model.GQLQueryParam;
import org.globsframework.graphql.model.GraphQlResponse;
import org.globsframework.graphql.parser.GqlField;
import org.globsframework.http.GlobHttpContent;
import org.globsframework.http.HttpServerRegister;
import org.globsframework.http.HttpTreatmentWithHeader;
import org.globsframework.http.openapi.model.GlobOpenApi;
import org.globsframework.http.server.apache.GlobHttpApacheBuilder;
import org.globsframework.http.server.apache.Server;
import org.globsframework.json.GSonUtils;
import org.globsframework.json.annottations.IsJsonContent;
import org.globsframework.sql.*;
import org.globsframework.sql.annotations.DbTableName;
import org.globsframework.sql.constraints.Constraint;
import org.globsframework.sql.constraints.Constraints;
import org.globsframework.sql.drivers.jdbc.DataSourceSqlService;
import org.globsframework.sql.drivers.jdbc.DbType;
import org.globsframework.sql.drivers.jdbc.MappingHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.concurrent.Executors.newThreadPerTaskExecutor;

/*
start with following argument (or none for in memory)

--dbUrl jdbc:hsqldb:file:./db/ --user sa --password ""
or
--dbUrl jdbc:postgresql://localhost/postgres --user postgres --password xxxx

The code create and populates empty db.

Then you can query db with graphQl:
curl 'http://localhost:4000/api/graphql' --data-binary '{"query":"{\n  professors: professors{\n   uuid\n    firstName\n    lastName\n    mainClasses{\n      name\n      students{\n        totalCount\n      }\n    }\n  }\n  allClasses: classes{\n     name\n     students{\n      totalCount\n       edges{\n         node{\n           firstName\n           lastName\n         }\n       }\n     }\n   }\n}","variables":{}}'

The code expose on default port 4000 a
REST api route /api/{class, student, professor} en post/put/get
OPEN API under /api/openapi
GRAPHQL route under /api/graphql
 */

public class Example2 {

    public static final Logger LOGGER = LoggerFactory.getLogger(Example2.class);

    public static void main(String[] args) throws InterruptedException {

        // parse argument with default value.
        Glob argument = ParseCommandLine.parse(ArgumentType.TYPE, args);

        // retrieve jdbc url, user, etc to init Hikari pool.
        DbType dbType = DbType.fromString(argument.getNotEmpty(ArgumentType.dbUrl));
        HikariConfig configuration = new HikariConfig();
        configuration.setUsername(argument.getNotEmpty(ArgumentType.user));
        configuration.setPassword(argument.get(ArgumentType.password));
        configuration.setJdbcUrl(argument.getNotEmpty(ArgumentType.dbUrl));

        // create a SqlService based on datasource.
        // SqlService is the entry point to access the db.
        SqlService sqlService = new DataSourceSqlService(
                MappingHelper.get(dbType), new HikariDataSource(configuration), dbType);

        // list resources we managed (for db and api)
        GlobType[] resources = {DbStudentType.TYPE, DbProfessorType.TYPE, DbClassType.TYPE};
        {
            SqlConnection db = sqlService.getDb();
            //create tables if they do not exist in db.
            Arrays.asList(resources).forEach(db::createTable);
            db.commitAndClose();
        }
        {
            //fill db with 20 students and 2 classes
            populate(sqlService);
        }

        // Create a virtual thread as graphql code is mostly db access.
        ThreadFactory factory = Thread.ofVirtual().name("GQL").factory();

        // create a globs graphql builder where we register loader to fetch db data.
        GQLGlobCallerBuilder<DbContext> gqlGlobCallerBuilder = new GQLGlobCallerBuilder<DbContext>(
                newThreadPerTaskExecutor(factory)
        );

        // loader from root (so without parent).
        gqlGlobCallerBuilder.registerLoader(QueryType.professor, (gqlField, callContext, parents) -> {
            load(gqlField, parents, EntityQuery.uuid, DbProfessorType.TYPE, DbProfessorType.uuid, callContext);
            return CompletableFuture.completedFuture(null);
        });

        gqlGlobCallerBuilder.registerLoader(QueryType.class_, (gqlField, callContext, parents) -> {
            load(gqlField, parents, EntityQuery.uuid, DbClassType.TYPE, DbClassType.uuid, callContext);
            return CompletableFuture.completedFuture(null);
        });

        gqlGlobCallerBuilder.registerLoader(QueryType.student, (gqlField, callContext, parents) -> {
            load(gqlField, parents, EntityQuery.uuid, DbStudentType.TYPE, DbStudentType.uuid, callContext);
            return CompletableFuture.completedFuture(null);
        });

        // search from root (still without parent).
        gqlGlobCallerBuilder.registerLoader(QueryType.professors, (gqlField, callContext, parents) -> {
            search(gqlField, parents, callContext, DbProfessorType.TYPE, DbProfessorType.firstName, DbProfessorType.lastName);
            return CompletableFuture.completedFuture(null);
        });

        gqlGlobCallerBuilder.registerLoader(QueryType.classes, (gqlField, callContext, parents) -> {
            search(gqlField, parents, callContext, DbClassType.TYPE, DbClassType.name);
            return CompletableFuture.completedFuture(null);
        });

        gqlGlobCallerBuilder.registerLoader(QueryType.students, (gqlField, callContext, parents) -> {
            search(gqlField, parents, callContext, DbStudentType.TYPE, DbStudentType.firstName, DbStudentType.lastName);
            return CompletableFuture.completedFuture(null);
        });

        // loader from point in the tree (with one or many parents).
        gqlGlobCallerBuilder.registerLoader(GQLProfessor.mainClasses, (gqlField, callContext, parents) ->
                loadFromParent(parents, DbProfessorType.uuid, callContext, DbClassType.principalProfessorUUID, DbClassType.TYPE));

        gqlGlobCallerBuilder.registerLoader(GQLClass.principalProfessor, (gqlField, callContext, parents) ->
                loadFromParent(parents, DbClassType.principalProfessorUUID, callContext, DbProfessorType.uuid, DbProfessorType.TYPE));

        gqlGlobCallerBuilder.registerLoader(GQLStudent.class_, (gqlField, callContext, parents) ->
                loadFromParent(parents, DbStudentType.mainClassUUID, callContext, DbClassType.uuid, DbClassType.TYPE));

        // register a connection. All the standard fields associated with the cursor management are handle generically.
        // the base64 of the cursor contain the json for id/idValue and sortField/sortValue
        gqlGlobCallerBuilder.registerConnection(GQLClass.students, (gqlField, callContext, parents) -> {
            parents.forEach(p ->
                    ConnectionBuilder.withDbKey(DbStudentType.uuid)
                            .withParam(Parameter.EMPTY, Parameter.after,
                                    Parameter.first, Parameter.before,
                                    Parameter.last, Parameter.skip)
                            .withOrder(Parameter.orderBy, Parameter.order)
                            .scanAll(gqlField, p, null, callContext.dbConnection));
            return CompletableFuture.completedFuture(null);
        }, DbStudentType.uuid, Parameter.orderBy);


        // create an HttpServerRegister to register Http end point.
        final HttpServerRegister httpServerRegister = new HttpServerRegister("EstablishmentServer/0.1");

        // for each resource we register post, put, get.
        for (GlobType resource : resources) {
            httpServerRegister.register("/api/" + resource.getName(), null)
                    .post(resource, null, (body, pathParameters, queryParameters) -> {
                        long start = System.nanoTime();
                        String status = "OK";
                        // get the key
                        StringField keyField = resource.getKeyFields()[0].asStringField();
                        String uuid = UUID.randomUUID().toString();
                        SqlConnection db = sqlService.getDb();
                        try {
                            //an insert into request
                            CreateBuilder createBuilder = db.getCreateBuilder(resource);

                            for (Field field : resource.getFields()) {
                                //ignore key field.
                                if (!field.isKeyField()) {
                                    //if value is set add it to the insert request.
                                    body.getOptValue(field).ifPresent(v -> createBuilder.setObject(field, v));
                                }
                            }

                            // add uuid
                            createBuilder.set(keyField, uuid);
                            try (SqlRequest insertRequest = createBuilder.getRequest()) {
                                // execute the request.
                                insertRequest.apply();
                            }
                            return retrieveResource(resource, db, keyField, uuid);
                        } catch (Exception e) {
                            status = "ERROR";
                            throw e;
                        } finally {
                            db.commitAndClose();
                            publishToES(argument, "POST", resource.getName(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), status);
                        }
                    })
                    .declareReturnType(resource);

            HttpServerRegister.Verb onUrl = httpServerRegister.register("/api/" + resource.getName() + "/{uuid}", UrlType.TYPE);
            onUrl.put(resource, null, (body, pathParameters, queryParameters) -> {
                        long start = System.nanoTime();
                        String status = "OK";
                        SqlConnection db = sqlService.getDb();
                        StringField keyField = resource.getKeyFields()[0].asStringField();
                        String uuid = pathParameters.getNotEmpty(UrlType.uuid);
                        UpdateBuilder updateBuilder = db.getUpdateBuilder(resource, Constraints.equal(keyField, uuid));

                        for (Field field : resource.getFields()) {
                            if (!field.isKeyField()) {
                                body.getOptValue(field).ifPresent(v -> updateBuilder.updateUntyped(field, v));
                            }
                        }

                        try (SqlRequest insertRequest = updateBuilder.getRequest()) {
                            insertRequest.apply();
                            return retrieveResource(resource, db, keyField, uuid);
                        } catch (Exception e) {
                            status = "ERROR";
                            throw e;
                        } finally {
                            db.commit();
                            publishToES(argument, "PUT", resource.getName(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), status);
                        }
                    })
                    .declareReturnType(resource);

            onUrl.get(null, (body, pathParameters, queryParameters) -> {
                        long start = System.nanoTime();
                        String status = "OK";
                        SqlConnection db = sqlService.getDb();
                        StringField keyField = resource.getKeyFields()[0].asStringField();
                        String uuid = pathParameters.getNotEmpty(UrlType.uuid);
                        try {
                            return retrieveResource(resource, db, keyField, uuid);
                        } catch (Exception e) {
                            status = "ERROR";
                            throw e;
                        } finally {
                            publishToES(argument, "GET", resource.getName(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), status);
                        }
                    })
                    .declareReturnType(resource);

            onUrl.delete(null, (body, pathParameters, queryParameters) -> {
                long start = System.nanoTime();
                String status = "OK";
                SqlConnection db = sqlService.getDb();
                StringField keyField = resource.getKeyFields()[0].asStringField();
                String uuid = pathParameters.getNotEmpty(UrlType.uuid);
                try (SqlRequest deleteRequest = db.getDeleteRequest(resource, Constraints.equal(keyField, uuid))) {
                    deleteRequest.apply();
                    return CompletableFuture.completedFuture(null);
                } catch (Exception e) {
                    status = "ERROR";
                    throw e;
                } finally {
                    db.commitAndClose();
                    publishToES(argument, "DELETE", resource.getName(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), status);
                }
            });
        }

        // we register now the entry point for graphQL.

        // we generate the graphql schema.
        // to load it in the graphql library to response to query on schema.
        SchemaParser schemaParser = new SchemaParser();
        SchemaGenerator schemaGenerator = new SchemaGenerator();
        GlobSchemaGenerator globSchemaGenerator = new GlobSchemaGenerator(SchemaType.TYPE, new DefaultGlobModel(Parameter.TYPE, EntityQuery.TYPE, SearchQuery.TYPE));
        final String s = globSchemaGenerator.generateAll();
        LOGGER.info("Schema is\n" + s);
        final TypeDefinitionRegistry typeDefinitionRegistry = schemaParser.parse(s);
        GraphQLSchema graphQLSchema = schemaGenerator.makeExecutableSchema(typeDefinitionRegistry, RuntimeWiring.MOCKED_WIRING);
        GraphQL gql = GraphQL.newGraphQL(graphQLSchema).build();

        GQLGlobCaller<DbContext> gqlGlobCaller =
                gqlGlobCallerBuilder.build(SchemaType.TYPE, new DefaultGlobModel(Parameter.TYPE, EntityQuery.TYPE, SearchQuery.TYPE));
        httpServerRegister.register("/api/graphql", null)
                .post(GraphQlRequest.TYPE, null, null, new HttpTreatmentWithHeader() {
                    final Gson gson = new Gson();

                    public CompletableFuture<Glob> consume(Glob body, Glob url, Glob queryParameters, Glob header) throws Exception {
                        long start = System.nanoTime();
                        String query = body.get(GraphQlRequest.query);

                        // hack to response to query on schema.
                        if (query.contains("__schema")) {
                            final ExecutionResult execute = gql.execute(query);
                            final Map<String, Object> stringObjectMap = execute.toSpecification();
                            final String s1 = gson.toJson(stringObjectMap);
                            return CompletableFuture.completedFuture(GlobHttpContent.TYPE.instantiate()
                                    .set(GlobHttpContent.content, s1.getBytes(StandardCharsets.UTF_8)));
                        }

                        // manage variables.
                        String v = body.get(GraphQlRequest.variables);
                        Map<String, String> variables = new HashMap<>();
                        if (Strings.isNotEmpty(v)) {
                            JsonReader jsonReader = new JsonReader(new StringReader(v));
                            JsonElement jsonElement = JsonParser.parseReader(jsonReader);
                            JsonObject asJsonObject = jsonElement.getAsJsonObject();
                            Set<Map.Entry<String, JsonElement>> entries = asJsonObject.entrySet();
                            for (Map.Entry<String, JsonElement> entry : entries) {
                                variables.put(entry.getKey(), gson.toJson(entry.getValue()));
                            }
                        }

                        // handle graphql request.
                        DbContext gqlContext = new DbContext(sqlService.getAutoCommitDb());
                        return gqlGlobCaller.query(query, variables, gqlContext)
                                .thenApply(glob -> GraphQlResponse.TYPE.instantiate().set(GraphQlResponse.data, GSonUtils.encode(glob, false)))
                                .handle((response, throwable) -> {
                                    gqlContext.dbConnection.commitAndClose();
                                    long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                                    if (throwable != null) {
                                        publishToES(argument, "GRAPHQL", "graphql", duration, "ERROR");
                                        return GraphQlResponse.TYPE.instantiate()
                                                .set(GraphQlResponse.errorMessage, throwable.getMessage());
                                    } else {
                                        publishToES(argument, "GRAPHQL", "graphql", duration, "OK");
                                        return response;
                                    }
                                });
                    }
                });

        httpServerRegister.register("/ping", null)
                .get(null, null, new HttpTreatmentWithHeader() {

                    @Override
                    public CompletableFuture<Glob> consume(Glob body, Glob url, Glob queryParameters, Glob headerType) throws Exception {
//                        System.out.println("Example2.consume");
                        return CompletableFuture.completedFuture(GlobHttpContent.TYPE.instantiate()
                                .set(GraphQlResponse.data, GSonUtils.encode(body, false)));
                    }
                });


        // register openAPI entrypoint on /api
        httpServerRegister.registerOpenApi(new GlobOpenApi(httpServerRegister));

        H2ServerBootstrap h2ServerBootstrap = H2ServerBootstrap.bootstrap()
                .setH2Config(H2Config.DEFAULT)
                .setIOReactorConfig(IOReactorConfig.custom().setSoReuseAddress(true).build());

        GlobHttpApacheBuilder globHttpApacheBuilder = new GlobHttpApacheBuilder(httpServerRegister);
        final Server server =
                globHttpApacheBuilder.startAndWaitForStartup(h2ServerBootstrap,
                        argument.get(ArgumentType.port, 4000));

        System.out.println("Listen on port: " + server.getPort());
        synchronized (System.out) {
            System.out.wait();
        }
    }

    private static void publishToES(Glob argument, String verb, String resource, long duration, String status) {
        String esUrl = argument.get(ArgumentType.esUrl);
        if (Strings.isNullOrEmpty(esUrl)) {
            return;
        }
        String esIndex = argument.get(ArgumentType.esIndex);
        MutableGlob stats = StatsType.TYPE.instantiate()
                .set(StatsType.verb, verb)
                .set(StatsType.resource, resource)
                .set(StatsType.duration, duration)
                .set(StatsType.date, ZonedDateTime.now())
                .set(StatsType.status, status);

        CompletableFuture.runAsync(() -> {
            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
                String targetUrl = esUrl + "/" + esIndex + "/_doc";
                HttpPost post = new HttpPost(targetUrl);
                post.setEntity(new StringEntity(GSonUtils.encode(stats, false), ContentType.APPLICATION_JSON));
                httpClient.execute(post, response -> null);
            } catch (Exception e) {
                LOGGER.error("Failed to publish to ES", e);
            }
        });
    }

    private static void populate(SqlService sqlService) {
        final SqlConnection db = sqlService.getDb();
        final SelectBuilder queryBuilder = db.getQueryBuilder(DbStudentType.TYPE);
        final LongAccessor count = queryBuilder.count(DbStudentType.uuid);
        try (SelectQuery query = queryBuilder.getQuery()) {
            final GlobStream execute = query.execute();
            if (execute.next() && count.getLong() != 0) {
                db.commitAndClose();
                return;
            }
        }

        Glob prof_1 = DbProfessorType.TYPE.instantiate().set(DbProfessorType.firstName, "Jones").set(DbProfessorType.lastName, "David")
                .set(DbProfessorType.uuid, UUID.randomUUID().toString());
        Glob prof_2 = DbProfessorType.TYPE.instantiate().set(DbProfessorType.firstName, "Williams").set(DbProfessorType.lastName, "Jessica")
                .set(DbProfessorType.uuid, UUID.randomUUID().toString());
        db.populate(Arrays.asList(prof_1, prof_2));
        final MutableGlob class1_a = DbClassType.TYPE.instantiate().set(DbClassType.name, "1-a")
                .set(DbClassType.principalProfessorUUID, prof_1.get(DbProfessorType.uuid))
                .set(DbClassType.uuid, UUID.randomUUID().toString());
        final MutableGlob class1_b = DbClassType.TYPE.instantiate().set(DbClassType.name, "2-a")
                .set(DbClassType.principalProfessorUUID, prof_2.get(DbProfessorType.uuid))
                .set(DbClassType.uuid, UUID.randomUUID().toString());
        db.populate(Arrays.asList(class1_a, class1_b));
        String[] surnames = {"Smith", "Johnson", "Williams", "Brown", "Jones", "Miller", "Davis", "Garcia", "Rodriguez",
                "Wilson", "Martinez", "Anderson", "Taylor", "Thomas", "Moore", "Jackson", "White", "Harris", "Thompson", "Lewis"};
        String[] firstNames = {"James", "John", "Robert", "Michael", "William", "David", "Richard", "Joseph", "Charles",
                "Thomas", "Mary", "Patricia", "Jennifer", "Linda", "Elizabeth", "Barbara", "Susan", "Jessica", "Sarah", "Karen"};
        List<Glob> globs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            globs.add(DbStudentType.TYPE.instantiate()
                    .set(DbStudentType.uuid, UUID.randomUUID().toString())
                    .set(DbStudentType.firstName, firstNames[i])
                    .set(DbStudentType.lastName, surnames[i])
                    .set(DbStudentType.mainClassUUID, i < 10 ? class1_a.get(DbClassType.uuid) : class1_b.get(DbClassType.uuid)));
        }
        db.populate(globs);
        db.commitAndClose();
    }

    private static void search(GqlField gqlField, List<OnLoad> parents, DbContext callContext, GlobType dbType, StringField... fields) {
        Optional<String> searchValue = gqlField.field().parameters().map(SearchQuery.search);
        try (SelectQuery query = callContext.dbConnection.getQueryBuilder(dbType,
                        searchValue.map(s -> Constraints.or(
                                        Arrays.stream(fields).map(f -> Constraints.containsIgnoreCase(f, s)).toArray(Constraint[]::new)))
                                .orElse(null)
                )
                .selectAll()
                .getQuery()) {
            try (Stream<Glob> globStream = query.executeAsGlobStream()) {
                globStream.forEach(parents.getFirst().onNew()::push);
            }
        }
    }

    private static CompletableFuture<Void> loadFromParent(List<OnLoad> parents, StringField mainClassUUID, DbContext dbContext, StringField uuid, GlobType dbType) {
        Map<String, List<OnLoad>> toQuery =
                parents.stream().collect(
                        Collectors.groupingBy(onLoad ->
                                onLoad.parent().get(mainClassUUID)));
        SqlConnection db = dbContext.dbConnection;
        try (SelectQuery query = db.getQueryBuilder(dbType, Constraints.in(uuid, toQuery.keySet()))
                .selectAll()
                .getQuery()) {
            try (Stream<Glob> globStream = query.executeAsGlobStream()) {
                globStream.forEach(d -> toQuery.getOrDefault(d.get(uuid), List.of())
                        .forEach(onLoad -> onLoad.onNew().push(d)));
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<Glob> retrieveResource(GlobType resource, SqlConnection db, StringField keyField, String uuid) {
        Glob createdData;
        // sql select * from 'resource' where uuid='uuidValue'
        try (SelectQuery query = db.getQueryBuilder(resource, Constraints.equal(keyField, uuid))
                .selectAll()
                .getQuery()) {
            createdData = query.executeUnique();
        } finally {
            db.commitAndClose();
        }
        return CompletableFuture.completedFuture(createdData);
    }

    private static void load(GqlField gqlField, List<OnLoad> parents, StringField paramUUIDField, GlobType dbType,
                             StringField dbUUID, DbContext dbContext) {
        String uuid = gqlField.field().parameters().map(paramUUIDField).orElseThrow();
        SqlConnection db = dbContext.dbConnection;
        try (SelectQuery query = db.getQueryBuilder(dbType, Constraints.equal(dbUUID, uuid))
                .selectAll()
                .getQuery()) {
            parents.getFirst()
                    .onNew()
                    .push(query.executeUnique());
        }
    }

    public static class DbClassType {
        public static final GlobType TYPE;

        public static final StringField uuid;

        public static final StringField name;

        public static final StringField principalProfessorUUID;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("class");
            typeBuilder.addAnnotation(DbTableName.create("classes"));
            uuid = typeBuilder.declareStringField("uuid", KeyField.ZERO);
            name = typeBuilder.declareStringField("name");
            principalProfessorUUID = typeBuilder.declareStringField("principalProfessorUUID");
            TYPE = typeBuilder.build();
        }
    }

    public static class DbProfessorType {
        public static final GlobType TYPE;

        public static final StringField uuid;

        public static final StringField firstName;

        public static final StringField lastName;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("professor");
            typeBuilder.addAnnotation(DbTableName.create("professors"));
            uuid = typeBuilder.declareStringField("uuid", KeyField.ZERO);
            firstName = typeBuilder.declareStringField("firstName");
            lastName = typeBuilder.declareStringField("lastName");
            TYPE = typeBuilder.build();
        }
    }

    public static class DbStudentType {
        public static final GlobType TYPE;

        public static final StringField uuid;

        public static final StringField firstName;

        public static final StringField lastName;

        public static final StringField mainClassUUID;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("student");
            typeBuilder.addAnnotation(DbTableName.create("students"));
            uuid = typeBuilder.declareStringField("uuid", KeyField.ZERO);
            firstName = typeBuilder.declareStringField("firstName");
            lastName = typeBuilder.declareStringField("lastName");
            mainClassUUID = typeBuilder.declareStringField("mainClassUUID");
            TYPE = typeBuilder.build();
        }
    }

    public static class UrlType {
        public static final GlobType TYPE;

        public static final StringField uuid;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("url");
            uuid = typeBuilder.declareStringField("uuid");
            TYPE = typeBuilder.build();
        }
    }

    public static class StatsType {
        public static final GlobType TYPE;
        public static final StringField verb;
        public static final StringField resource;
        public static final LongField duration;
        public static final DateTimeField date;
        public static final StringField status;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("Stats");
            verb = typeBuilder.declareStringField("verb");
            resource = typeBuilder.declareStringField("resource");
            duration = typeBuilder.declareLongField("duration");
            date = typeBuilder.declareDateTimeField("date");
            status = typeBuilder.declareStringField("status");
            TYPE = typeBuilder.build();
        }
    }

    public static class ArgumentType {
        public static final GlobType TYPE;

        public static final StringField dbUrl;

        public static final StringField user;

        public static final StringField password;

        public static final IntegerField port;

        public static final StringField esUrl;

        public static final StringField esIndex;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("argument");
            dbUrl = typeBuilder.declareStringField("dbUrl", DefaultString.create("jdbc:hsqldb:mem:db"));
            user = typeBuilder.declareStringField("user", DefaultString.create("sa"));
            password = typeBuilder.declareStringField("password", DefaultString.create(""));
            port = typeBuilder.declareIntegerField("port");
            esUrl = typeBuilder.declareStringField("esUrl", DefaultString.create(""));
            esIndex = typeBuilder.declareStringField("esIndex", DefaultString.create("stats"));
            TYPE = typeBuilder.build();
        }
    }

    public static class SchemaType {
        public static final GlobType TYPE;

        public static final GlobField<QueryType> query;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("schema");
            query = typeBuilder.declareGlobField("query", () -> QueryType.TYPE);
            TYPE = typeBuilder.build();
        }
    }

    public static class QueryType {
        public static final GlobType TYPE;

        public static final GlobArrayField<GQLProfessor> professors;

        public static final GlobArrayField<GQLClass> classes;

        public static final GlobArrayField<GQLStudent> students;

        public static final GlobField<GQLProfessor> professor;

        public static final GlobField<GQLClass> class_;

        public static final GlobField<GQLStudent> student;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("query");
            professors = typeBuilder.declareGlobArrayField("professors", () -> GQLProfessor.TYPE,
                    GQLQueryParam.create(SearchQuery.TYPE));
            classes = typeBuilder.declareGlobArrayField("classes", () -> GQLClass.TYPE,
                    GQLQueryParam.create(SearchQuery.TYPE));
            students = typeBuilder.declareGlobArrayField("students", () -> GQLStudent.TYPE,
                    GQLQueryParam.create(SearchQuery.TYPE));
            professor = typeBuilder.declareGlobField("professor", () -> GQLProfessor.TYPE,
                    GQLQueryParam.create(EntityQuery.TYPE));
            class_ = typeBuilder.declareGlobField("class", () -> GQLClass.TYPE,
                    GQLQueryParam.create(EntityQuery.TYPE));
            student = typeBuilder.declareGlobField("student", () -> GQLStudent.TYPE,
                    GQLQueryParam.create(EntityQuery.TYPE));
            TYPE = typeBuilder.build();
        }
    }

    public static class SearchQuery {
        public static final GlobType TYPE;

        public static final StringField search;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("search");
            search = typeBuilder.declareStringField("search");
            TYPE = typeBuilder.build();
        }
    }

    public static class EntityQuery {
        public static final GlobType TYPE;

        public static final StringField uuid;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("entity");
            uuid = typeBuilder.declareStringField("uuid");
            TYPE = typeBuilder.build();
        }
    }

    public static class GQLClass {
        public static final GlobType TYPE;

        public static final StringField uuid;

        public static final StringField name;

        public static final GlobField<GQLProfessor> principalProfessor;

        public static final GlobField<StudentConnection> students;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("GQLClass");
            uuid = typeBuilder.declareStringField("uuid");
            name = typeBuilder.declareStringField("name");
            principalProfessor = typeBuilder.declareGlobField("principalProfessor", () -> GQLProfessor.TYPE);
            students = typeBuilder.declareGlobField("students", () -> StudentConnection.TYPE,
                    GQLQueryParam.create(Parameter.TYPE));
            TYPE = typeBuilder.build();
        }
    }

    public static class GQLStudent {
        public static final GlobType TYPE;

        public static final StringField uuid;

        public static final StringField firstName;

        public static final StringField lastName;

        public static final GlobField<GQLClass> class_;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("GQLStudent");
            uuid = typeBuilder.declareStringField("uuid");
            firstName = typeBuilder.declareStringField("firstName");
            lastName = typeBuilder.declareStringField("lastName");
            class_ = typeBuilder.declareGlobField("class", () -> GQLClass.TYPE);
            TYPE = typeBuilder.build();
        }
    }

    public static class GQLProfessor {
        public static final GlobType TYPE;

        public static final StringField uuid;

        public static final StringField firstName;

        public static final StringField lastName;

        public static final GlobArrayField<GQLClass> mainClasses;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("GQLProfessor");
            uuid = typeBuilder.declareStringField("uuid");
            firstName = typeBuilder.declareStringField("firstName");
            lastName = typeBuilder.declareStringField("lastName");
            mainClasses = typeBuilder.declareGlobArrayField("mainClasses", () -> GQLClass.TYPE);
            TYPE = typeBuilder.build();
        }
    }

    public static class StudentConnection {
        public static final GlobType TYPE;

        public static final IntegerField totalCount;

        public static final GlobArrayField<StudentHedge> edges;

        public static final GlobField<GQLPageInfo> pageInfo;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("StudentConnection");
            totalCount = typeBuilder.declareIntegerField("totalCount");
            edges = typeBuilder.declareGlobArrayField("edges", () -> StudentHedge.TYPE);
            pageInfo = typeBuilder.declareGlobField("pageInfo", () -> GQLPageInfo.TYPE);
            TYPE = typeBuilder.build();
        }
    }

    public static class StudentHedge {
        public static final GlobType TYPE;

        public static final GlobField<GQLStudent> node;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("StudentHedge");
            node = typeBuilder.declareGlobField("node", () -> GQLStudent.TYPE);
            TYPE = typeBuilder.build();
        }
    }

    public static class Parameter {
        public static final GlobType TYPE;

        public static final IntegerField first;

        public static final StringField after;

        public static final IntegerField last;

        public static final StringField before;

        public static final IntegerField skip;

        public static final StringField order; // asc, desc ?

        public static final StringField orderBy; //

        public static final Glob EMPTY;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("parameter");
            first = typeBuilder.declareIntegerField("first");
            after = typeBuilder.declareStringField("after");
            last = typeBuilder.declareIntegerField("last");
            before = typeBuilder.declareStringField("before");
            skip = typeBuilder.declareIntegerField("skip");
            order = typeBuilder.declareStringField("order");
            orderBy = typeBuilder.declareStringField("orderBy");
            TYPE = typeBuilder.build();
            EMPTY = TYPE.instantiate();
//            GlobTypeLoaderFactory.create(Parameter.class).load();
        }
    }

    public static class GraphQlRequest {
        public static final GlobType TYPE;

        public static final StringField query;

        public static final StringField variables;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("request");
            query = typeBuilder.declareStringField("query");
            variables = typeBuilder.declareStringField("variables", IsJsonContent.UNIQUE_GLOB);
            TYPE = typeBuilder.build();
        }
    }

    static class DbContext implements GQLGlobCaller.GQLContext {
        final SqlConnection dbConnection;

        public DbContext(SqlConnection dbConnection) {
            this.dbConnection = dbConnection;
        }
    }
}
