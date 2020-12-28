package io.branchtalk.discussions.model

import java.net.URI

import cats.effect.Sync
import cats.{ Order, Show }
import eu.timepit.refined.types.string.NonEmptyString
import enumeratum._
import enumeratum.EnumEntry.Hyphencase
import eu.timepit.refined.api.Refined
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.numeric.NonNegative
import io.estatico.newtype.macros.newtype
import io.scalaland.catnip.Semi
import io.branchtalk.ADT
import io.branchtalk.shared.model.{ FastEq, ParseRefined, ShowPretty }

trait PostProperties { self: Post.type =>
  type UrlTitle           = PostProperties.UrlTitle
  type Title              = PostProperties.Title
  type URL                = PostProperties.URL
  type Text               = PostProperties.Text
  type Content            = PostProperties.Content
  type CommentsNr         = PostProperties.CommentsNr
  type Upvotes            = PostProperties.Upvotes
  type Downvotes          = PostProperties.Downvotes
  type TotalScore         = PostProperties.TotalScore
  type ControversialScore = PostProperties.ControversialScore
  type Sorting            = PostProperties.Sorting
  val UrlTitle           = PostProperties.UrlTitle
  val Title              = PostProperties.Title
  val URL                = PostProperties.URL
  val Text               = PostProperties.Text
  val Content            = PostProperties.Content
  val CommentsNr         = PostProperties.CommentsNr
  val Upvotes            = PostProperties.Upvotes
  val Downvotes          = PostProperties.Downvotes
  val TotalScore         = PostProperties.TotalScore
  val ControversialScore = PostProperties.ControversialScore
  val Sorting            = PostProperties.Sorting
}
object PostProperties {

  @newtype final case class UrlTitle(nonEmptyString: NonEmptyString)
  object UrlTitle {
    def unapply(urlTitle: UrlTitle): Option[NonEmptyString] = urlTitle.nonEmptyString.some
    def parse[F[_]: Sync](string: String): F[UrlTitle] =
      ParseRefined[F].parse[NonEmpty](string).map(UrlTitle.apply)

    implicit val show: Show[UrlTitle] = (t: UrlTitle) => s"UrlTitle(${t.nonEmptyString.value.show})"
    implicit val order: Order[UrlTitle] = (x: UrlTitle, y: UrlTitle) =>
      x.nonEmptyString.value compareTo y.nonEmptyString.value
  }

  @newtype final case class Title(nonEmptyString: NonEmptyString)
  object Title {
    def unapply(title: Title): Option[NonEmptyString] = title.nonEmptyString.some
    def parse[F[_]: Sync](string: String): F[Title] =
      ParseRefined[F].parse[NonEmpty](string).map(Title.apply)

    implicit val show:  Show[Title]  = (t: Title) => s"Title(${t.nonEmptyString.value.show})"
    implicit val order: Order[Title] = (x: Title, y: Title) => x.nonEmptyString.value compareTo y.nonEmptyString.value
  }

  @newtype final case class URL(uri: URI)
  object URL {
    def unapply(url: URL): Option[URI] = url.uri.some

    implicit val show:  Show[URL]  = (t: URL) => t.uri.toString
    implicit val order: Order[URL] = (x: URL, y: URL) => x.uri compareTo y.uri
  }

  @newtype final case class Text(string: String)
  object Text {
    def unapply(text: Text): Option[String] = text.string.some

    implicit val show:  Show[Text]  = (t: Text) => t.string
    implicit val order: Order[Text] = (x: Text, y: Text) => x.string compareTo y.string
  }

  @Semi(FastEq, ShowPretty) sealed trait Content extends ADT
  object Content {
    final case class Url(url: Post.URL) extends Content
    final case class Text(text: Post.Text) extends Content

    @Semi(FastEq, ShowPretty) sealed trait Type extends EnumEntry with Hyphencase
    object Type extends Enum[Type] {
      case object Url extends Type
      case object Text extends Type

      val values: IndexedSeq[Type] = findValues
    }
    @newtype final case class Raw(string: String)
    object Raw {
      def unapply(raw: Raw): Option[String] = raw.string.some

      implicit val show:  Show[Raw]  = (t: Raw) => t.string
      implicit val order: Order[Raw] = (x: Raw, y: Raw) => x.string compareTo y.string
    }

    object Tupled {
      def apply(contentType: Type, contentText: Raw): Content = contentType match {
        case Type.Url  => Content.Url(Post.URL(URI.create(contentText.string)))
        case Type.Text => Content.Text(Post.Text(contentText.string))
      }

      def unpack(content: Content): (Type, Raw) = content match {
        case Content.Url(url)   => Type.Url -> Raw(url.uri.toString)
        case Content.Text(text) => Type.Text -> Raw(text.string)
      }

      def unapply(content: Content): Option[(Type, Raw)] = unpack(content).some
    }
  }

  @newtype final case class CommentsNr(toNonNegativeInt: Int Refined NonNegative)
  object CommentsNr {
    def unapply(commentsNr: CommentsNr): Option[Int Refined NonNegative] = commentsNr.toNonNegativeInt.some

    implicit val show: Show[CommentsNr] = (t: CommentsNr) => t.toNonNegativeInt.value.toString
    implicit val order: Order[CommentsNr] = (x: CommentsNr, y: CommentsNr) =>
      x.toNonNegativeInt.value compareTo y.toNonNegativeInt.value
  }

  @newtype final case class Upvotes(toNonNegativeInt: Int Refined NonNegative)
  object Upvotes {
    def unapply(upvotes: Upvotes): Option[Int Refined NonNegative] = upvotes.toNonNegativeInt.some

    implicit val show: Show[Upvotes] = (t: Upvotes) => t.toNonNegativeInt.value.toString
    implicit val order: Order[Upvotes] = (x: Upvotes, y: Upvotes) =>
      x.toNonNegativeInt.value compareTo y.toNonNegativeInt.value
  }

  @newtype final case class Downvotes(toNonNegativeInt: Int Refined NonNegative)
  object Downvotes {
    def unapply(downvotes: Downvotes): Option[Int Refined NonNegative] = downvotes.toNonNegativeInt.some

    implicit val show: Show[Downvotes] = (t: Downvotes) => t.toNonNegativeInt.value.toString
    implicit val order: Order[Downvotes] = (x: Downvotes, y: Downvotes) =>
      x.toNonNegativeInt.value compareTo y.toNonNegativeInt.value
  }

  @newtype final case class TotalScore(toInt: Int)
  object TotalScore {
    def unapply(totalScore: TotalScore): Option[Int] = totalScore.toInt.some

    implicit val show:  Show[TotalScore]  = (t: TotalScore) => t.toInt.toString
    implicit val order: Order[TotalScore] = (x: TotalScore, y: TotalScore) => x.toInt compareTo y.toInt
  }

  @newtype final case class ControversialScore(toInt: Int)
  object ControversialScore {
    def unapply(controversialScore: ControversialScore): Option[Int] = controversialScore.toInt.some

    implicit val show: Show[ControversialScore] = (t: ControversialScore) => t.toInt.toString
    implicit val order: Order[ControversialScore] = (x: ControversialScore, y: ControversialScore) =>
      x.toInt compareTo y.toInt
  }

  sealed trait Sorting extends EnumEntry
  object Sorting extends Enum[Sorting] {
    case object Newest extends Sorting
    case object TotalScore extends Sorting
    case object ControversialScore extends Sorting

    val values = findValues
  }
}
