package graphql.execution.instrumentation.export

import graphql.GraphQL
import graphql.StarWarsSchema
import spock.lang.Specification

class ExportedVariablesInstrumentationTest extends Specification {

    def "exported variables are captured via plural names"() {

        def collector = new PluralExportedVariablesCollector()
        def exportVariablesInstrumentation = new ExportedVariablesInstrumentation({ -> collector })

        def graphQL = GraphQL.newGraphQL(StarWarsSchema.starWarsSchema)
                .instrumentation(exportVariablesInstrumentation)
                .build()

        given:
        def executionResult = graphQL.execute("""
            query {
                hero 
                {
                    id @export(as:"droidId")
                    name 
                    friends  @export(as:"r2d2Friends") 
                    {
                        name @export(as:"friendNames")
                    }
                }
            }
        """)

        expect:
        executionResult.getErrors().size() == 0

        Map<String, Object> exportedVariables = collector.getVariables()

        exportedVariables.size() == 3
        exportedVariables['droidId'] == "2001"
        exportedVariables['r2d2Friends'] == [
                [name: "Luke Skywalker"],
                [name: "Han Solo"],
                [name: "Leia Organa"],
        ]
        exportedVariables['friendNames'] == [
                "Luke Skywalker",
                "Han Solo",
                "Leia Organa",
        ]
    }

    def "exported variables feed into future queries"() {

        def collector = new PluralExportedVariablesCollector()
        def exportVariablesInstrumentation = new ExportedVariablesInstrumentation({ -> collector })

        def graphQL = GraphQL.newGraphQL(StarWarsSchema.starWarsSchema)
                .instrumentation(exportVariablesInstrumentation)
                .build()

        given:
        graphQL.execute('''
            query A {
                hero 
                {
                    id @export(as:"droidId")
                    name 
                    friends  @export(as:"r2d2Friends") 
                    {
                        name @export(as:"friendNames")
                    }
                }
            }
        ''')
        def executionResult = graphQL.execute('''
            query B($droidId : String!) {
                droid (id : $droidId ) {
                    name
                }
            }
        ''')

        expect:
        executionResult.getErrors().size() == 0

        executionResult.data == [
                droid: [
                        name: "r2d2"
                ]
        ]


    }
}
