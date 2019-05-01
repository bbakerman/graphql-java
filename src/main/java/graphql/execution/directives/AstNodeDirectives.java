package graphql.execution.directives;

import graphql.Internal;
import graphql.introspection.Introspection;
import graphql.language.DirectivesContainer;
import graphql.schema.GraphQLDirective;

import java.util.Map;

/**
 * Directives can be hierarchical and can occur not just directly on a field but only above it in the query tree.
 * <p>
 * So imagine a (quite pathological query) like the following and the field "review"
 *
 * <pre>
 * {@code
 *   fragment Details on Book @timeout(afterMillis: 25) {       # container = fragment definition ; distance = 1
 *       title
 *       review @timeout(afterMillis: 5)                        # container = field ; distance = 0
 *   }
 *
 *   query Books @timeout(afterMillis: 30) {                    # container = operation definition ; distance = 3
 *       books(searchString: "monkey") {
 *           id
 *           ...Details @timeout(afterMillis: 20)               # container = fragment spread; distance = 2
 *           review @timeout(afterMillis: 10)                   # container = field ; distance = 0
 *       }
 *   }
 *   }
 * </pre>
 *
 * @apiNote This class is internal and highly like to change in a future version.  Use it at your own peril
 */
@Internal
public interface AstNodeDirectives extends Comparable<AstNodeDirectives> {

    /**
     * @return the query AST node that contained the directives
     */
    DirectivesContainer getDirectivesContainer();

    /**
     * @return the map of resolved directives on that AST element
     */
    Map<String, GraphQLDirective> getDirectives();

    /**
     * Currently this is contentious in terms of its calculation and is HIGHLY likely to change
     * in the future.
     *
     * @return the distance from the originating field where 0 is on the field itself
     */
    int getDistance();

    /**
     * @return an enum of the location of the directive
     */
    Introspection.DirectiveLocation getDirectiveLocation();

    /**
     * This will create a new AstNodeDirectives that filters our the list of directives to a specifically named
     * directive and otherwise keeps the other information the same
     *
     * @param directiveName the named directive
     *
     * @return a copy that only contains the named directive
     */
    AstNodeDirectives restrictTo(String directiveName);
}
