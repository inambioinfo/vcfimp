package ca.innovativemedicine.vcf.solr

import java.io.File

import java.{ util => ju }

import scala.collection.JavaConverters._

import ca.innovativemedicine.vcf._
import ca.innovativemedicine.vcf.parsers._

import org.apache.solr.common.SolrInputDocument

import scala.collection.mutable

import com.typesafe.config.ConfigFactory



object SolrVcfImporter {
  def apply(): SolrVcfImporter = new SolrVcfImporter with ConfiguredSolrServerProvider with Configured {
    lazy val config = ConfigFactory.load()
  }
}

trait SolrVcfImporter { self: SolrServerProvider =>
  def importFile(file: File) {
    val parser = VcfParser()
    
    parser.parse(file, skipErrors=false) { (vcfInfo, rows) =>
      val reference = vcfInfo.reference getOrElse {
        throw new IllegalStateException("Missing required ##reference in VCF.")
      }
      
      val docs: Iterator[SolrInputDocument] = rows flatMap { case (variant, formats, gtData) =>
        (vcfInfo.samples zip gtData) map { case (sample, values) =>
          val doc = new SolrInputDocument()
          
          doc.addField("id", ju.UUID.randomUUID().toString)
          
          // Location.
          
          doc.addField("reference", reference)
          doc.addField("chromosome", variant.chromosome.fold(_.id.toString, identity))
          doc.addField("position", variant.position)
          
          // Sample.
          
          doc.addField("sample", sample.id.id)
          
          // Variant.
          
          doc.addField("v_ids", variant.ids.asJava)
          doc.addField("v_ref", variant.reference)
          doc.addField("v_alt", variant.alternates map (_.fold(_.fold(_.toBreakendString, _.id.toString), identity)))
          variant.quality foreach (doc.addField("v_quality", _))
          variant.filter foreach {
            case FilterResult.Pass =>
              doc.addField("v_filters_failed", new ju.LinkedList[String]())
            case FilterResult.Fail(filters) =>
              doc.addField("v_filters_failed", filters.asJava)
          }
          
          // Call.
          
          val data = ((formats map (_.id.id)) zip values).toMap
          val ploidy = (data get "GT") collect {
            case VcfString(gt) :: Nil =>
              val unphased = (gt split "[|/]") map (_.toInt)
              doc.addField("s_gt_unphased", unphased.toList.asJava)
              doc.addField("s_gt", gt)
              unphased.size
          } getOrElse 2
          
          (data get "GL") foreach { uvals =>
            val likelihoods = uvals collect { case VcfFloat(value) => value }
            val order = orderGenotypes(ploidy, variant.alternates.size + 1) map (_ mkString "_")
            
            (order zip likelihoods) foreach { case (ord, gtl) =>
              doc.addField("s_gl_" + ord, gtl)
            }
          }
          
          doc
        }
      }
      
      withSolrServer { solr =>
        docs foreach { solr add _ }
      }
    }
  }
  
  
  // TODO: This should be be an LRU map or something.
  val cache = new mutable.HashMap[(Int, Int), List[List[Int]]] with mutable.SynchronizedMap[(Int, Int), List[List[Int]]]
  
  def orderGenotypes(ploidy: Int, alleles: Int): List[List[Int]] = {
    require(ploidy >= 1)
    require(alleles >= 1)
    
    def order(ploidy: Int, alts: Int): List[List[Int]] = if (ploidy == 1) {
      ((0 to alts) map (_ :: Nil)).toList
    } else {
      ((0 to alts) flatMap { j =>
        order(ploidy - 1, j) map (j :: _)
      }).toList
    }
    
    cache.getOrElseUpdate((ploidy, alleles), order(ploidy, alleles - 1) map (_.reverse))
  }
}