# Globs Examples — HTTP, REST, OpenAPI, GraphQL

Runnable samples wiring the [Globs](https://globsframework.org) libraries together: `globs-commandline` to
read the arguments, `globs-sql` for the database, `globs-http` for the REST routes and the generated OpenAPI
description, `globs-graphql` for the GraphQL endpoint — all of them driven by the same `GlobType`s, with no
DTO and no reflection anywhere.

The artifact is `http-rest-graphql`; it is a demo module, not a library to depend on.

## Requirements

- Java 21
- a JDBC database, or nothing: the samples default to a file-based HSQLDB under `./db/`

## The three samples

| Class | What it shows |
| --- | --- |
| `org.globsframework.sample.rest.Example1` | the smallest thing: one POST route inserting into a table and returning the created row, plus `/api/openapi` |
| `org.globsframework.sample.graphql.Example2` | REST + OpenAPI + GraphQL over three related tables (students, classes, professors), with connections, paging and virtual threads |
| `org.globsframework.sample.generic.GenericApiExpose` | the same server, but the `GlobType`s are **read from a JSON model file at startup** instead of being written in Java — the whole API is data |

`org.globsframework.sample.rest.ClientHttp` is a small load client (10 000 GETs on `/api/greeting`, latency
percentiles) used against `Example1`.

### Example1 — one table, one route

```bash
mvn -o package
java -cp target/classes:... org.globsframework.sample.rest.Example1 \
     --dbUrl jdbc:hsqldb:file:./db/ --user sa --password ""
```

Listens on port 3100 (`--port` to change it) and exposes `POST /student`, `GET /api/greeting` and
`/api/openapi`. The `GlobType` is the whole declaration — the table, the JSON body and the OpenAPI schema all
come from it:

```java
GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("Student");
typeBuilder.addAnnotation(DbTableName.create("students"));
id = typeBuilder.declareIntegerField("id", KeyField.ZERO, AutoIncrement.INSTANCE);
firstName = typeBuilder.declareStringField("firstName");
lastName = typeBuilder.declareStringField("lastName");
TYPE = typeBuilder.build();
```

```java
httpServerRegister.register("/student", null)
        .post(StudentType.TYPE, null, (body, url, queryParameters) -> { /* insert, then re-read */ })
        .declareReturnType(StudentType.TYPE);
```

### Example2 — REST + OpenAPI + GraphQL

```bash
java ... org.globsframework.sample.graphql.Example2 \
     --dbUrl jdbc:hsqldb:file:./db/ --user sa --password ""
# or
java ... org.globsframework.sample.graphql.Example2 \
     --dbUrl jdbc:postgresql://localhost/postgres --user postgres --password xxxx
```

Creates the tables if needed, populates 20 students and 2 classes, and listens on port 4000:

- `POST/PUT/GET /api/{class, student, professor}` — REST
- `/api/openapi` — the generated OpenAPI description
- `/api/graphql` — GraphQL, loaders registered per query field on `GQLGlobCallerBuilder`

```bash
curl 'http://localhost:4000/api/graphql' --data-binary \
  '{"query":"{ professors { uuid firstName lastName mainClasses { name students { totalCount } } } }","variables":{}}'
```

### GenericApiExpose — the model as data

Same endpoints, but nothing is declared in Java: `--model src/main/resources/model.json` is read at startup
into `GlobType`s (through `globs-gson`'s GlobType-to-JSON format), and the REST routes, the OpenAPI document,
the GraphQL schema and the SQL tables are all derived from it. Annotations travel in the JSON as globs —
`KeyField`, `DbTableName`, and the sample's own `Searchable`, `IsConnection`, `Link`, `DbTarget`:

```json
{ "name": "firstName", "type": "string", "annotations": [ { "_kind": "Searchable" } ] }
```

```bash
java ... org.globsframework.sample.generic.GenericApiExpose --model src/main/resources/model.json \
     --dbUrl jdbc:hsqldb:file:./db/ --user sa --password ""
```

It is also the `mainClass` of the assembly and of the GraalVM `native-maven-plugin` profile;
`reachability-metadata.json` and `resource-config.json` are the native-image configuration for it.

## Building

```bash
mvn -o package                  # jar
mvn -o package -Passembly       # jar-with-dependencies, main class GenericApiExpose
```

The module depends on snapshots of `globs`, `globs-commandline`, `globs-http`, `globs-sql` and
`globs-graphql`: `mvn install` those repositories first, or align the versions in `pom.xml` with what is in
`~/.m2`.

## Links

- [Globs Framework](https://globsframework.org)
- [globs-http](https://github.com/globsframework/globs-http) · [globs-sql](https://github.com/globsframework/globs-db) · [globs-graphql](https://github.com/globsframework/globs-graphql)
