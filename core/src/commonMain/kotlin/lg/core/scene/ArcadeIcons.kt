package lg.core.scene

/**
 * 80s-arcade tile icons: chunky pixel sprites in neon colors, one 12x12 grid
 * per game (see [PixelArt]). Drawn on the dark catalogue card by the platform.
 */
internal object ArcadeIcons {
    fun snake(scene: Scene, size: Float): Unit = PixelArt.draw(scene, size, SPRITE_SNAKE)
    fun fusion(scene: Scene, size: Float): Unit = PixelArt.draw(scene, size, SPRITE_FUSION)
    fun wallSmash(scene: Scene, size: Float): Unit = PixelArt.draw(scene, size, SPRITE_WALLSMASH)
    fun colorEcho(scene: Scene, size: Float): Unit = PixelArt.draw(scene, size, SPRITE_COLORECHO)
    fun twins(scene: Scene, size: Float): Unit = PixelArt.draw(scene, size, SPRITE_TWINS)
    fun tileShuffle(scene: Scene, size: Float): Unit = PixelArt.draw(scene, size, SPRITE_TILESHUFFLE)
    fun threeInLine(scene: Scene, size: Float): Unit = PixelArt.draw(scene, size, SPRITE_THREEINLINE)
    fun paddles(scene: Scene, size: Float): Unit = PixelArt.draw(scene, size, SPRITE_PADDLES)
    fun dropFour(scene: Scene, size: Float): Unit = PixelArt.draw(scene, size, SPRITE_DROPFOUR)
    fun chirp(scene: Scene, size: Float): Unit = PixelArt.draw(scene, size, SPRITE_CHIRP)
    fun hiddenBombs(scene: Scene, size: Float): Unit = PixelArt.draw(scene, size, SPRITE_HIDDENBOMBS)
    fun secretWord(scene: Scene, size: Float): Unit = PixelArt.draw(scene, size, SPRITE_SECRETWORD)
    fun flipDiscs(scene: Scene, size: Float): Unit = PixelArt.draw(scene, size, SPRITE_FLIPDISCS)
    fun nineByNine(scene: Scene, size: Float): Unit = PixelArt.draw(scene, size, SPRITE_NINEBYNINE)
    fun pixelReveal(scene: Scene, size: Float): Unit = PixelArt.draw(scene, size, SPRITE_PIXELREVEAL)

    private val SPRITE_SNAKE: Array<String> = arrayOf(
        "............",
        ".gggggggggg.",
        ".g..........",
        ".g......rr..",
        ".g......rr..",
        ".gggggggggg.",
        "..........g.",
        "..........g.",
        "ywggggggggg.",
        "yy..........",
        "............",
        "............",
    )

    private val SPRITE_FUSION: Array<String> = arrayOf(
        "yyyyy.ooooo.",
        "yyyyy.ooooo.",
        "yywyy.oowoo.",
        "yyyyy.ooooo.",
        "yyyyy.ooooo.",
        "............",
        "ppppp.rrrrr.",
        "ppppp.rrrrr.",
        "ppwpp.rrwrr.",
        "ppppp.rrrrr.",
        "ppppp.rrrrr.",
        "............",
    )

    private val SPRITE_WALLSMASH: Array<String> = arrayOf(
        "rrr.rrr.rrr.",
        "ooo.ooo.ooo.",
        "yyy.yyy.yyy.",
        "ggg.ggg.ggg.",
        "............",
        "............",
        ".....ww.....",
        ".....ww.....",
        "............",
        "............",
        "...cccccc...",
        "............",
    )

    private val SPRITE_COLORECHO: Array<String> = arrayOf(
        "............",
        "...gg..rr...",
        "..ggg..rrr..",
        ".gggg..rrrr.",
        ".gggg..rrrr.",
        "............",
        "............",
        ".yyyy..cccc.",
        ".yyyy..cccc.",
        "..yyy..ccc..",
        "...yy..cc...",
        "............",
    )

    private val SPRITE_TWINS: Array<String> = arrayOf(
        "............",
        "............",
        "............",
        "wwwww..wwwww",
        "wp.pw..wp.pw",
        "wpppw..wpppw",
        "w.p.w..w.p.w",
        "wwwww..wwwww",
        "............",
        "............",
        "............",
        "............",
    )

    private val SPRITE_TILESHUFFLE: Array<String> = arrayOf(
        "ccc.ppp.yyy.",
        "ccc.ppp.yyy.",
        "ccc.ppp.yyy.",
        "............",
        "ggg.ooo.uuu.",
        "ggg.ooo.uuu.",
        "ggg.ooo.uuu.",
        "............",
        "rrr.www.....",
        "rrr.www.....",
        "rrr.www.....",
        "............",
    )

    private val SPRITE_THREEINLINE: Array<String> = arrayOf(
        "p.pc...cyyy.",
        ".p.c...cy.y.",
        "p.pc...cyyy.",
        "ccccccccccc.",
        "...cyyyc....",
        "...cy.yc....",
        "...cyyyc....",
        "ccccccccccc.",
        "yyyc...cp.p.",
        "y.yc...c.p..",
        "yyyc...cp.p.",
        "............",
    )

    private val SPRITE_PADDLES: Array<String> = arrayOf(
        ".....dd.....",
        "............",
        ".pp..dd.....",
        ".pp.........",
        ".pp..dd.....",
        ".pp...ww.cc.",
        ".pp..dww.cc.",
        ".........cc.",
        ".....dd..cc.",
        ".........cc.",
        ".....dd.....",
        "............",
    )

    private val SPRITE_DROPFOUR: Array<String> = arrayOf(
        "dd.dd.dd.dd.",
        "dd.dd.dd.dd.",
        "............",
        "dd.yy.dd.dd.",
        "dd.yy.dd.dd.",
        "............",
        "yy.pp.dd.yy.",
        "yy.pp.dd.yy.",
        "............",
        "pp.pp.pp.pp.",
        "pp.pp.pp.pp.",
        "............",
    )

    private val SPRITE_CHIRP: Array<String> = arrayOf(
        "............",
        "............",
        "...yyyyy....",
        "..yyyyyyyy..",
        ".yyyyyywdyoo",
        ".yooyyyyyyoo",
        ".yoooyyyyy..",
        "..yoooyyy...",
        "...yyyyy....",
        "....o.o.....",
        "............",
        "............",
    )

    private val SPRITE_HIDDENBOMBS: Array<String> = arrayOf(
        ".........y..",
        "........yry.",
        ".......o.y..",
        "......o.....",
        "....uuuuu...",
        "...uuuuuuu..",
        "..uuwwuuuuu.",
        "..uuwuuuuuu.",
        "..uuuuuuuuu.",
        "...uuuuuuu..",
        "....uuuuu...",
        "............",
    )

    private val SPRITE_SECRETWORD: Array<String> = arrayOf(
        "............",
        ".y..ppp.ggg.",
        "y.y...p..g..",
        "yyy..pp..g..",
        "y.y......g..",
        "y.y..p...g..",
        "............",
        "............",
        "ccc.ccc.ccc.",
        "ccc.ccc.ccc.",
        "............",
        "............",
    )

    private val SPRITE_FLIPDISCS: Array<String> = arrayOf(
        ".ppp....ccc.",
        "ppppp..ccccc",
        "ppppp..ccccc",
        "ppppp..ccccc",
        ".ppp....ccc.",
        "............",
        ".ccc....ppp.",
        "ccccc..ppppp",
        "ccccc..ppppp",
        "ccccc..ppppp",
        ".ccc....ppp.",
        "............",
    )

    private val SPRITE_NINEBYNINE: Array<String> = arrayOf(
        "............",
        ".cccccccccc.",
        ".cy.c.pc..c.",
        ".c..cw.c.yc.",
        ".cccccccccc.",
        ".c.pc..cw.c.",
        ".c..cy.c..c.",
        ".cccccccccc.",
        ".cw.c..c..c.",
        ".c..c.yc.pc.",
        ".cccccccccc.",
        "............",
    )

    private val SPRITE_PIXELREVEAL: Array<String> = arrayOf(
        "............",
        "....cyc.c.c.",
        "....c.c.c.c.",
        "............",
        "............",
        "cc...pp.pp..",
        "....ppppppp.",
        "cc..ppppppp.",
        ".....ppppp..",
        "cc....ppp...",
        ".......p....",
        "............",
    )
}
