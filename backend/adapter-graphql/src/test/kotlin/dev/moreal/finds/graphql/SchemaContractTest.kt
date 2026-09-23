package dev.moreal.finds.graphql

import graphql.Scalars
import graphql.schema.GraphQLScalarType
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType

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
      RuntimeWiring.newRuntimeWiring().scalar(dateTime)
        .type("Node") { it.typeResolver { null } }.build(),
    )

    assertEquals(setOf("node", "jobPosting", "jobPostings", "careerSite", "careerSites", "skill", "skills", "crawlStatuses"),
      schema.queryType.fieldDefinitions.map { it.name }.toSet())
    assertEquals(
      setOf("registerCareerSite", "triggerCrawl"),
      requireNotNull(schema.mutationType).fieldDefinitions.map { it.name }.toSet(),
    )
    assertNotNull(schema.getType("PostingFilterInput"))
    assertNotNull(schema.getType("JobPostingConnection"))
    assertNotNull(schema.getType("ApiErrorCode"))
    val node = schema.getType("Node") as? GraphQLInterfaceType
    assertNotNull(node)
    for (name in listOf("JobPosting", "CareerSite", "User", "Skill", "CrawlRun", "AuditEvent")) {
      val type = schema.getType(name) as? GraphQLObjectType
      assertNotNull(type, name)
      assertEquals(listOf("Node"), type.interfaces.map { it.name })
      assertNotNull(type.getFieldDefinition("id"))
    }
    assertEquals(setOf("hasNextPage", "hasPreviousPage", "startCursor", "endCursor"),
      (schema.getType("PageInfo") as GraphQLObjectType).fieldDefinitions.map { it.name }.toSet())
  }
}
