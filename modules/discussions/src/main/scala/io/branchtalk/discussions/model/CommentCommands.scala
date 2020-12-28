package io.branchtalk.discussions.model

import io.scalaland.catnip.Semi
import io.branchtalk.shared.model.{ FastEq, ID, ShowPretty, Updatable }

trait CommentCommands { self: Comment.type =>
  type Create     = CommentCommands.Create
  type Update     = CommentCommands.Update
  type Delete     = CommentCommands.Delete
  type Restore    = CommentCommands.Restore
  type Upvote     = CommentCommands.Upvote
  type Downvote   = CommentCommands.Downvote
  type RevokeVote = CommentCommands.RevokeVote
  val Create     = CommentCommands.Create
  val Update     = CommentCommands.Update
  val Delete     = CommentCommands.Delete
  val Restore    = CommentCommands.Restore
  val Upvote     = CommentCommands.Upvote
  val Downvote   = CommentCommands.Downvote
  val RevokeVote = CommentCommands.RevokeVote
}
object CommentCommands {

  @Semi(FastEq, ShowPretty) final case class Create(
    authorID: ID[User],
    postID:   ID[Post],
    content:  Comment.Content,
    replyTo:  Option[ID[Comment]]
  )

  @Semi(FastEq, ShowPretty) final case class Update(
    id:         ID[Comment],
    editorID:   ID[User],
    newContent: Updatable[Comment.Content]
  )

  @Semi(FastEq, ShowPretty) final case class Delete(
    id:       ID[Comment],
    editorID: ID[User]
  )

  @Semi(FastEq, ShowPretty) final case class Restore(
    id:       ID[Comment],
    editorID: ID[User]
  )

  @Semi(FastEq, ShowPretty) final case class Upvote(
    id:      ID[Comment],
    voterID: ID[User]
  )

  @Semi(FastEq, ShowPretty) final case class Downvote(
    id:      ID[Comment],
    voterID: ID[User]
  )

  @Semi(FastEq, ShowPretty) final case class RevokeVote(
    id:      ID[Comment],
    voterID: ID[User]
  )
}
