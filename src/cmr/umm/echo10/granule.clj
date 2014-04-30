(ns cmr.umm.echo10.granule
  "Contains functions for parsing and generating the ECHO10 dialect."
  (:require [clojure.data.xml :as x]
            [clojure.java.io :as io]
            [cmr.common.xml :as cx]
            [cmr.umm.granule :as g]
            [cmr.umm.echo10.granule.temporal :as gt]
            [cmr.umm.echo10.granule.product-specific-attribute-ref :as psa]
            [cmr.umm.echo10.granule.orbit-calculated-spatial-domain :as ocsd]
            [cmr.umm.xml-schema-validator :as v]
            [cmr.umm.echo10.core])
  (:import cmr.umm.granule.UmmGranule))

(defn xml-elem->project-refs
  "Parses an ECHO10 Campaigns element of a Granule XML document and returns the short names."
  [granule-elem]
  (seq (cx/strings-at-path
                granule-elem
                [:Campaigns :Campaign :ShortName])))

(defn generate-project-refs
  "Generates the Campaigns element of an ECHO10 XML from a UMM Granule project-refs entry."
  [prefs]
  (when (not (empty? prefs))
    (x/element :Campaigns {}
               (for [pref prefs]
                 (x/element :Campaign {}
                            (x/element :ShortName {} pref))))))


(defn- xml-elem->CollectionRef
  "Returns a UMM ref element from a parsed Granule XML structure"
  [granule-content-node]
  (if-let [entry-title (cx/string-at-path granule-content-node [:Collection :DataSetId])]
    (g/collection-ref entry-title)
    (let [short-name (cx/string-at-path granule-content-node [:Collection :ShortName])
          version-id (cx/string-at-path granule-content-node [:Collection :VersionId])]
      (g/collection-ref short-name version-id))))

(defn- xml-elem->Granule
  "Returns a UMM Product from a parsed Granule XML structure"
  [xml-struct]
  (let [coll-ref (xml-elem->CollectionRef xml-struct)]
    (g/map->UmmGranule {:granule-ur (cx/string-at-path xml-struct [:GranuleUR])
                        :collection-ref coll-ref
                        :temporal (gt/xml-elem->Temporal xml-struct)
                        :orbit-calculated-spatial-domains (ocsd/xml-elem->orbit-calculated-spatial-domains xml-struct)
                        :project-refs (xml-elem->project-refs xml-struct)
                        :product-specific-attributes (psa/xml-elem->ProductSpecificAttributeRefs xml-struct)})))

(defn parse-granule
  "Parses ECHO10 XML into a UMM Granule record."
  [xml]
  (xml-elem->Granule (x/parse-str xml)))

(extend-protocol cmr.umm.echo10.core/UmmToEcho10Xml
  UmmGranule
  (umm->echo10-xml
    [granule]
    (let [{{:keys [entry-title short-name version-id]} :collection-ref
           granule-ur :granule-ur
           temporal :temporal
           ocsds :orbit-calculated-spatial-domains
           prefs :project-refs
           psas :product-specific-attributes} granule]
      (x/emit-str
        (x/element :Granule {}
                   (x/element :GranuleUR {} granule-ur)
                   (x/element :InsertTime {} "2012-12-31T19:00:00Z")
                   (x/element :LastUpdate {} "2013-11-30T19:00:00Z")
                   (cond (not (nil? entry-title))
                         (x/element :Collection {}
                                    (x/element :DataSetId {} entry-title))
                         :else (x/element :Collection {}
                                          (x/element :ShortName {} short-name)
                                          (x/element :VersionId {} version-id)))
                   (x/element :RestrictionFlag {} "0.0")
                   (gt/generate-temporal temporal)
                   (ocsd/generate-orbit-calculated-spatial-domains ocsds)
                   (generate-project-refs prefs)
                   (psa/generate-product-specific-attribute-refs psas)
                   (x/element :Orderable {} "true"))))))

(defn validate-xml
  "Validates the XML against the Granule ECHO10 schema."
  [xml]
  (v/validate-xml (io/resource "schema/echo10/Granule.xsd") xml))


