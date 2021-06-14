package graphics.scenery.attribute

/**
 * Delegation type class.
 *
 * Delegations can be handled either as [OncePerDelegate], meaning that even if many nodes refer to the same
 * delegate, that delegate will be drawn only once. If [ForEachNode] is chosen, the delegate will be drawn for
 * every node that refers to it.
 *
 * @author Ulrik Guenther <hello@ulrik.is>
 */
enum class DelegationType {
    /** Will render the node only once per target delegate. */
    OncePerDelegate,
    /** Will render for each node independent of referring to the same delegate. */
    ForEachNode
}
