package de.fosd.typechef.typesystem.linker

import de.fosd.typechef.parser.Position
import de.fosd.typechef.featureexpr.FeatureExprFactory.{base, dead}
import de.fosd.typechef.featureexpr.{FeatureModel, FeatureExprFactory, FeatureExpr}
import de.fosd.typechef.typesystem.CType

/**
 * describes the linker interface for a file, i.e. all imported (and used)
 * signatures and all exported signatures.
 */
case class CInterface(
                         featureModel: FeatureExpr,
                         importedFeatures: Set[String],
                         declaredFeatures: Set[String], //not inferred
                         imports: Seq[CSignature],
                         exports: Seq[CSignature]) {


    def this(imports: Seq[CSignature], exports: Seq[CSignature]) = this(base, Set(), Set(), imports, exports)

    override def toString =
        "fm " + featureModel + "\n" +
            "features (" + importedFeatures.size + ")\n\t" + importedFeatures.toList.sorted.mkString(", ") +
            (if (declaredFeatures.isEmpty) "" else "declared features (" + declaredFeatures.size + ")\n\t" + declaredFeatures.toList.sorted.mkString(", ")) +
            "\nimports (" + imports.size + ")\n" + imports.map("\t" + _.toString).sorted.mkString("\n") +
            "\nexports (" + exports.size + ")\n" + exports.map("\t" + _.toString).sorted.mkString("\n") + "\n"

    lazy val importsByName = imports.groupBy(_.name)
    lazy val exportsByName = exports.groupBy(_.name)


    lazy val getInterfaceFeatures: Set[String] = {
        var result: Set[String] = Set()

        def addFeatures(featureExpr: FeatureExpr) {
            result = result ++ featureExpr.collectDistinctFeatures
        }

        addFeatures(featureModel)
        imports.map(s => addFeatures(s.fexpr))
        exports.map(s => addFeatures(s.fexpr))

        result
    }

    /**
     * removes duplicates by joining the corresponding conditions
     * removes imports that are available as exports in the same file
     * removes dead imports
     *
     * two elements are duplicate if they have the same name and type
     *
     * exports are not packed beyond removing dead exports.
     * duplicate exports are used for error detection
     */
    def pack: CInterface = if (isPacked) this
    else
        CInterface(featureModel, importedFeatures -- declaredFeatures, declaredFeatures,
            packImports, packExports).setPacked

    private var isPacked = false;
    private def setPacked() = {
        isPacked = true;
        this
    }
    private def packImports: Seq[CSignature] = {
        var importMap = Map[(String, CType), (FeatureExpr, Seq[Position])]()

        //eliminate duplicates with a map
        for (imp <- imports if ((featureModel and imp.fexpr).isSatisfiable())) {
            val key = (imp.name, imp.ctype)
            val old = importMap.getOrElse(key, (dead, Seq()))
            importMap = importMap + (key ->(old._1 or imp.fexpr, old._2 ++ imp.pos))
        }
        //eliminate imports that have corresponding exports
        for (exp <- exports) {
            val key = (exp.name, exp.ctype)
            if (importMap.contains(key)) {
                val (oldFexpr, oldPos) = importMap(key)
                val newFexpr = oldFexpr andNot exp.fexpr
                if ((featureModel and newFexpr).isSatisfiable())
                    importMap = importMap + (key ->(newFexpr, oldPos))
                else
                    importMap = importMap - key
            }
        }


        val r = for ((k, v) <- importMap.iterator)
        yield CSignature(k._1, k._2, v._1, v._2)
        r.toSeq
    }
    private def packExports: Seq[CSignature] = exports.filter(_.fexpr.and(featureModel).isSatisfiable())


    /**
     * ensures a couple of invariants.
     *
     * a module is illformed if
     * (a) it exports the same signature twice in the same configuration
     * (b) it imports the same signature twice in the same configuration
     * (c) if it exports and imports a name in the same configuration
     *
     * by construction, this should not occur in inferred and linked interfaces
     */
    def isWellformed: Boolean = {
        val exportsByName = exports.groupBy(_.name)
        val importsByName = imports.groupBy(_.name)

        var wellformed = true
        for (funName <- (exportsByName.keySet ++ importsByName.keySet)) {
            val sigs = exportsByName.getOrElse(funName, Seq()) ++ importsByName.getOrElse(funName, Seq())

            if (wellformed && !mutuallyExclusive(sigs)) {
                wellformed = false
                println(funName + " imported/exported multiple times in the same configuration: \n" + sigs.mkString("\t", "\n\t", "\n"))
            }
        }

        wellformed
    }


    def link(that: CInterface): CInterface =
        CInterface(
            this.featureModel and that.featureModel and inferConstraintsWith(that),
            this.importedFeatures ++ that.importedFeatures,
            this.declaredFeatures ++ that.declaredFeatures,
            this.imports ++ that.imports,
            this.exports ++ that.exports
        ).pack

    /**links without proper checks and packing. only for debugging purposes **/
    def debug_join(that: CInterface): CInterface =
        CInterface(
            this.featureModel and that.featureModel,
            this.importedFeatures ++ that.importedFeatures,
            this.declaredFeatures ++ that.declaredFeatures,
            this.imports ++ that.imports,
            this.exports ++ that.exports
        )


    /**
     * determines conflicts and returns corresponding name, feature expression and involved signatures
     *
     * conflicts are:
     * (a) both modules export the same name in the same configuration
     * (b) both modules import the same name with different types in the same configuration
     * (c) one module imports a name the other modules exports in the same configuration but with a different type
     *
     * returns any conflict (does not call a sat solver), even if the conditions are mutually exclusive.
     * the condition is true if there is NO conflict (it describes configurations without conflict)
     *
     * public only for debugging purposes
     */
    def getConflicts(that: CInterface): List[(String, FeatureExpr, Seq[CSignature])] =
        CInterface.presenceConflicts(this.exportsByName, that.exportsByName) ++
            CInterface.typeConflicts(this.importsByName, that.importsByName) ++
            CInterface.typeConflicts(this.importsByName, that.exportsByName) ++
            CInterface.typeConflicts(this.exportsByName, that.importsByName)

    /**
     * when there is an overlap in the exports, infer constraints which must be satisfied
     * to not have a problem
     */
    private def inferConstraintsWith(that: CInterface): FeatureExpr =
        getConflicts(that).foldLeft(FeatureExprFactory.base)(_ and _._2)


    def and(f: FeatureExpr): CInterface =
        CInterface(
            featureModel,
            importedFeatures,
            declaredFeatures,
            imports.map(_ and f),
            exports.map(_ and f)
        )

    def andFM(feature: FeatureExpr): CInterface = mapFM(_ and feature)
    def mapFM(f: FeatureExpr => FeatureExpr) = CInterface(f(featureModel), importedFeatures, declaredFeatures, imports, exports)


    /**
     * linking two well-formed models always yields a wellformed module, but it makes
     * only sense if the resulting feature model is not void.
     * hence compatibility is checked by checking the resulting feature model
     */
    def isCompatibleTo(that: CInterface): Boolean =
        (this link that).featureModel.isSatisfiable() &&
            this.declaredFeatures.intersect(that.declaredFeatures).isEmpty

    def isCompatibleTo(thatSeq: Seq[CInterface]): Boolean = {
        var m = this
        for (that <- thatSeq) {
            if (!(m isCompatibleTo that)) return false
            m = m link that
        }
        return true
    }


    /**
     * A variability-aware module is complete if it has no remaining imports with
     * satisfiable conditions and if the feature model is satisfiable (i.e., it allows to derive
     * at least one variant). A complete and fully-configured module is the desired end
     * result when configuring a product line for a specific use case.
     */
    def isComplete: Boolean = featureModel.isSatisfiable && pack.imports.isEmpty

    def isFullyConfigured: Boolean =
        pack.imports.forall(s => (featureModel implies s.fexpr).isTautology) &&
            pack.exports.forall(s => (featureModel implies s.fexpr).isTautology)

    /**
     * we can use a global feature model to ensure that composing modules
     * reflects the intended dependencies. This way, we can detect that we do not
     * accidentally restrict the product line more than intended by the domain expert
     * who designed the global feature model. We simply compare the feature model of
     * the linker result with a global model.
     */
    def compatibleWithGlobalFeatureModel(globalFM: FeatureExpr): Boolean =
        (globalFM implies featureModel).isTautology
    def compatibleWithGlobalFeatureModel(globalFM: FeatureModel): Boolean =
        featureModel.isTautology(globalFM)

    /**
     * turns the interface into a conditional interface (to emulate conditional
     * linking/composition of interfaces).
     *
     *
     * a.conditional(f) link b.conditional(g)
     *
     * is conceptually equivalent to
     *
     * if (f and g) a link b
     * else if (f and not g) a
     * else if (not f and g) b
     * else empty
     *
     * see text on conditional composition
     */
    def conditional(condition: FeatureExpr): CInterface =
        this.and(condition).mapFM(condition implies _)

    private def mutuallyExclusive(sigs: Seq[CSignature]): Boolean = if (sigs.size <= 1) true
    else {
        val pairs = for (a <- sigs.tails.take(sigs.size); b <- a.tail)
        yield (a.head.fexpr, b.fexpr)
        val formula = featureModel implies pairs.foldLeft(base)((a, b) => a and (b._1 mex b._2))
        formula.isTautology
    }

}

object CInterface {

    private[linker] def apply(fm: FeatureExpr, imp: Seq[CSignature], exp: Seq[CSignature]): CInterface =
        CInterface(fm, Set(), Set(), imp, exp)

    /**
     * signatures from a and b must not share a presence condition
     */
    private def presenceConflicts(a: Map[String, Seq[CSignature]], b: Map[String, Seq[CSignature]]): List[(String, FeatureExpr, Seq[CSignature])] = {
        var result: List[(String, FeatureExpr, Seq[CSignature])] = List()

        for (signame <- a.keys)
            if (b.contains(signame)) {
                val aa = a(signame)
                val bb = b(signame)
                val conflictExpr = disjointSigFeatureExpr(aa) mex disjointSigFeatureExpr(bb)
                result = (signame, conflictExpr, aa ++ bb) :: result
            }
        result
    }

    /**
     * signatures from a and b must not differ in type for the same configuration
     */
    private def typeConflicts(a: Map[String, Seq[CSignature]], b: Map[String, Seq[CSignature]]): List[(String, FeatureExpr, Seq[CSignature])] = {
        var result: List[(String, FeatureExpr, Seq[CSignature])] = List()

        for (signame <- a.keys)
            if (b.contains(signame)) {
                val aa = a(signame)
                val bb = b(signame)

                for (asig <- aa; bsig <- bb)
                    if (asig.ctype != bsig.ctype) //TODO use coerce
                        result = (signame, asig.fexpr mex bsig.fexpr, Seq(asig, bsig)) :: result
            }

        result
    }


    private def disjointSigFeatureExpr(a: Seq[CSignature]): FeatureExpr = a.foldLeft(dead)(_ or _.fexpr)


    //    /**
    //     * debugging information, underlying inferConstraints
    //     *
    //     * describes which method is exported twice under which constraints
    //     */
    //    def getConflicts(that: CInterface): Map[String, Seq[CSignature]] = {
    //        val aa = this.exports.groupBy(_.name)
    //        val bb = that.exports.groupBy(_.name)
    //        var result = Map[String, Seq[CSignature]]()
    //
    //        //two sets of signatures with the same name
    //        //(a1 or a2 or a3) mex (b1 or b2 or b3)
    //        def addConstraint(a: Seq[CSignature], b: Seq[CSignature]) =
    //            a.foldLeft(dead)(_ or _.fexpr) mex b.foldLeft(dead)(_ or _.fexpr)
    //
    //        for (signame <- aa.keys)
    //            if (bb.contains(signame)) {
    //                val c = addConstraint(aa(signame), bb(signame))
    //                if (!c.isSatisfiable())
    //                    result = result + (signame -> (aa(signame) ++ bb(signame)))
    //            }
    //        result
    //    }

}

object EmptyInterface extends CInterface(FeatureExprFactory.base, Set(), Set(), Seq(), Seq())