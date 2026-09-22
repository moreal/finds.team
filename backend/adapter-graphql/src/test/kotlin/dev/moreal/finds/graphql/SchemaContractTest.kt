package dev.moreal.finds.graphql

import graphql.Scalars
import graphql.schema.GraphQLScalarType
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SchemaContractTest {
  @Test
  fun `schema parses and exposes the complete root contract`() {
    val schemaText = requireNotNull(javaClass.getResource("/finds.graphqls")).readText()
    val registry = SchemaParser().parse(schemaText)
    val dateTime = GraphQLScalarType.newScalar()
      .name("DateTime")
      .coercing(Scalars.GraphQLString.coercing)
      .build()
    val schema = SchemaGenerator().makeExecutableSchema(
      registry,
      RuntimeWiring.newRuntimeWiring().scalar(dateTime).build(),
    )

    assertEquals(setOf("jobPostings", "crawlStatuses"), schema.queryType.fieldDefinitions.map { it.name }.toSet())
    assertEquals(
      setOf("registerCareerSite", "triggerCrawl"),
      requireNotNull(schema.mutationType).fieldDefinitions.map { it.name }.toSet(),
    )
    assertNotNull(schema.getType("PostingFilterInput"))
    assertNotNull(schema.getType("JobPostingConnection"))
    assertNotNull(schema.getType("ApiErrorCode"))
  }
}
