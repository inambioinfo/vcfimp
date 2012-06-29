package ca.innovativemedicine.vcf.parsers

import ca.innovativemedicine.vcf._

import scala.util.parsing.combinator.JavaTokenParsers


/**
 * Provides a suite of parsers that are able to parse parts of the variants
 * from a VCF file.
 */
trait VariantParsers extends TsvParsers with InfoParsers {
  import Variant._
  import FilterResult._
  import JoinType._
  
  
  // Parsers for parsing a whole variant from a row of a VCF file.
  
  
  def variant: Parser[Variant] =
    (chromosome & position & ids & ref & alternates & optional(quality) & optional(filters)) >> {
      
  	  case chr ~ pos ~ ids ~ ref ~ alt ~ qual ~ ftr =>
  	    val alleleCount = 1 + alt.size
  	    (tab ~> info(alleleCount)) ^^ { infoMap =>
  	      Variant(chr, pos, ids, ref, alt, qual, ftr, infoMap)
  	    }
  	    
  	}
    
  
  // Parsers for parsing individual fields from the variant-portion of a VCF file row.
  
  
  def chromosome: Parser[Chromosome] = (vcfId ^^ { Left(_) }) | ("[^:\\s]+".r ^^ { Right(_) })
  
  def position: Parser[Int] = "\\d+".r ^^ { _.toInt }
  
  def ids: Parser[List[String]] = '.' ^^^ Nil ||| repsep("[^\\s;]+".r, ";")
  
  def ref: Parser[String] = sequence
  
  def alternates: Parser[List[Alternate]] = rep1sep(
      (breakend ^^ { bnd => Left(Left(bnd)) })
    | (vcfId ^^ { id => Left(Right(id)) })
    | (sequence ^^ { Right(_) })
    , ',') 
  
  def quality: Parser[Double] = floatingPointNumber ^^ { _.toDouble }
  
  def filterName: Parser[Metadata.Filter] = "[^\\s;]+".r >> { name =>
    vcfInfo.getTypedMetadata[Metadata.Filter](VcfId(name)) match {
      case Some(filter) => success(filter)
      case None => err("Invalid filter <%s>; please define this filter in the VCF metadata" format name)
    }
  }
  
  def filters: Parser[FilterResult] = "PASS" ^^^ Pass | repsep(filterName, ";") ^^ (Fail(_))
  
  
  // Support/helper parsers.
  
  
  /** Indicates a value can be replaced with the "missing value" (ie. '.'). */
  def optional[A](p: => Parser[A]): Parser[Option[A]] = "." ^^^ None | p ^^ { Some(_) }
  
  def vcfId = "<" ~> ("[^<>]+".r) <~ ">" ^^ { VcfId(_) }
  
  def sequence: Parser[String] = "[atcgnATCGN]+".r ^^ { _.toUpperCase() }
  
  def chromosomePosition: Parser[Chromosome ~ Int] = (chromosome <~ ':') ~ position
  
  def breakend: Parser[Breakend] = (sequence ~ ("[" ~> chromosomePosition <~ "[") ^^
								     { case s ~ (c ~ p) => Breakend(s, c, p, JoinAfter) }
  
								   | ("]" ~> chromosomePosition <~ "]") ~ sequence ^^
								  	 { case c ~ p ~ s => Breakend(s, c, p, JoinBefore) }
								   
								   | sequence ~ ("]" ~> chromosomePosition <~ "]") ^^
								  	 { case s ~ (c ~ p) => Breakend(s, c, p, JoinReverseAfter) }
								   
								   | ("[" ~> chromosomePosition <~ "[") ~ sequence ^^
								     { case c ~ p ~ s => Breakend(s, c, p, JoinReverseBefore) }
								   )

}


object VariantParsers {
  def apply(vcf: VcfInfo): VariantParsers = new VariantParsers { val vcfInfo = vcf }
}