package com.example.hdip.contract;

import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.SchemaPrinter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the public GraphQL contract. Any schema change must be accompanied by a deliberate snapshot update:
 * {@code UPDATE_GRAPHQL_SNAPSHOT=true mvn -B -q test -Dtest=GraphQlSchemaSnapshotTest}.
 */
class GraphQlSchemaSnapshotTest {
    static final Path SCHEMA = Path.of("src", "main", "resources", "graphql", "schema.graphqls");
    static final Path SNAPSHOT = Path.of("src", "test", "resources", "graphql", "schema.snapshot.graphqls");

    @Test
    void schemaMatchesSnapshot() throws IOException {
        var registry = new SchemaParser().parse(Files.readString(SCHEMA, StandardCharsets.UTF_8));
        GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(registry, RuntimeWiring.newRuntimeWiring().build());
        String printed = new SchemaPrinter(SchemaPrinter.Options.defaultOptions()
                .includeDirectives(false).includeScalarTypes(false).includeSchemaDefinition(false)
                .descriptionsAsHashComments(false)).print(schema).replace("\r\n", "\n");

        if ("true".equals(System.getenv("UPDATE_GRAPHQL_SNAPSHOT"))) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, printed, StandardCharsets.UTF_8);
        }
        assertThat(SNAPSHOT).as("snapshot present; regenerate with UPDATE_GRAPHQL_SNAPSHOT=true").exists();
        assertThat(printed).isEqualTo(Files.readString(SNAPSHOT, StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void phiSensitiveQueriesStayDocumentedAsRestricted() throws IOException {
        String sdl = Files.readString(SCHEMA, StandardCharsets.UTF_8);
        assertThat(sdl).contains("CLINICIAN only").contains("AUDITOR only");
    }
}
