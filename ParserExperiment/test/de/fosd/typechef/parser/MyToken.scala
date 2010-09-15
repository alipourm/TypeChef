package de.fosd.typechef.parser
import de.fosd.typechef.featureexpr.FeatureExpr

class MyToken(val text: String, val feature: FeatureExpr) extends AbstractToken {
    def t() = text
    def getFeature = feature

    override def toString = "\"" + text + "\"" + (if (!feature.isBase) feature else "")
}
object EofToken extends MyToken("EOF", FeatureExpr.base)