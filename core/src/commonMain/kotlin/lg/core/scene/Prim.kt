package lg.core.scene

/**
 * Primitive kind constants stored in [Scene.kind]. Kept as top-level [Byte]
 * constants (rather than an enum) so [Scene.kind] can be a plain [ByteArray]
 * with zero boxing.
 */
public object Prim {
    public const val RECT: Byte = 0
    public const val CIRCLE: Byte = 1
    public const val LINE: Byte = 2
    public const val TEXT: Byte = 3
    public const val NUMBER: Byte = 4
    public const val LABEL: Byte = 5
    public const val ARC: Byte = 6
}
