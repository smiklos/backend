package io.branchtalk.discussions.api

import cats.data.NonEmptyList
import com.github.plokhotnyuk.jsoniter_scala.macros._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.NonNegative
import io.branchtalk.ADT
import io.branchtalk.api.JsoniterSupport._
import io.branchtalk.api.TapirSupport._
import io.branchtalk.discussions.model.{ Channel, Comment, Post, User }
import io.branchtalk.shared.model.{ ID, Updatable }
import io.scalaland.catnip.Semi
import io.scalaland.chimney.dsl._

@SuppressWarnings(Array("org.wartremover.warts.All")) // for macros
object CommentModels {

  // properties codecs
  implicit val commentContentCodec: JsCodec[Comment.Content] =
    summonCodec[String](JsonCodecMaker.make).asNewtype[Comment.Content]
  implicit val commentRepliesNrCodec: JsCodec[Comment.RepliesNr] =
    summonCodec[Int](JsonCodecMaker.make).refine[NonNegative].asNewtype[Comment.RepliesNr]

  // properties schemas
  implicit val commentContentSchema: JsSchema[Comment.Content] =
    summonSchema[String].asNewtype[Comment.Content]
  implicit val commentRepliesNrSchema: JsSchema[Comment.RepliesNr] =
    summonSchema[Int Refined NonNegative].asNewtype[Comment.RepliesNr]

  @Semi(JsCodec, JsSchema) sealed trait CommentError extends ADT
  object CommentError {

    @Semi(JsCodec, JsSchema) final case class BadCredentials(msg: String) extends CommentError
    @Semi(JsCodec, JsSchema) final case class NoPermission(msg: String) extends CommentError
    @Semi(JsCodec, JsSchema) final case class NotFound(msg: String) extends CommentError
    @Semi(JsCodec, JsSchema) final case class ValidationFailed(error: NonEmptyList[String]) extends CommentError
  }

  @Semi(JsCodec, JsSchema) final case class APIComment(
    id:        ID[Comment],
    authorID:  ID[User],
    channelID: ID[Channel],
    postID:    ID[Post],
    content:   Comment.Content,
    replyTo:   Option[ID[Comment]],
    repliesNr: Comment.RepliesNr
  )
  object APIComment {

    def fromDomain(comment: Comment): APIComment =
      comment.data.into[APIComment].withFieldConst(_.id, comment.id).transform
  }

  @Semi(JsCodec, JsSchema) final case class CreateCommentRequest(
    content: Comment.Content,
    replyTo: Option[ID[Comment]]
  )

  @Semi(JsCodec, JsSchema) final case class CreateCommentResponse(id: ID[Comment])

  @Semi(JsCodec, JsSchema) final case class UpdateCommentRequest(
    newContent: Updatable[Comment.Content]
  )

  @Semi(JsCodec, JsSchema) final case class UpdateCommentResponse(id: ID[Comment])

  @Semi(JsCodec, JsSchema) final case class DeleteCommentResponse(id: ID[Comment])

  @Semi(JsCodec, JsSchema) final case class RestoreCommentResponse(id: ID[Comment])
}
