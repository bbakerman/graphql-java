package benchmark.vavr;

import graphql.PublicApi;
import graphql.util.NodeLocation;
import io.vavr.collection.List;
import io.vavr.collection.Map;


/**
 * Adapts an arbitrary class to behave as a node.
 * We are using an Adapter because we don't want to require Nodes to implement a certain Interface.
 *
 * @param <T> the generic type of object
 */
@PublicApi
public interface NodeAdapter<T> {

    Map<String, List<T>> getNamedChildren(T node);

    T withNewChildren(T node, Map<String, List<T>> newChildren);

    T removeChild(T node, NodeLocation location);

}
