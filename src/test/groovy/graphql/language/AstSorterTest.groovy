package graphql.language

import graphql.TestUtil
import spock.lang.Specification

class AstSorterTest extends Specification {

    def "basic sorting works as expected"() {
        def query = '''
            query QZ {
                field(z: "valz", x : "valx", y:"valy") {
                    subfieldz
                    subfieldx
                    subfieldY
                }
            }

            query QX {
                field(z: "valz", x : "valx", y:"valy") {
                    subfieldz
                    subfieldx
                    subfieldY
                }
            }
                    
        '''

        def doc = TestUtil.parseQuery(query)

        when:
        def newDoc = new AstSorter().sortQuery(doc)
        then:
        newDoc == null

    }
}
