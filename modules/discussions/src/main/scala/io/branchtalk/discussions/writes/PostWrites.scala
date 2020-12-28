package io.branchtalk.discussions.writes

import io.branchtalk.discussions.model._
import io.branchtalk.shared.model._

trait PostWrites[F[_]] {

  def createPost(newPost:       Post.Create):     F[CreationScheduled[Post]]
  def updatePost(updatedPost:   Post.Update):     F[UpdateScheduled[Post]]
  def deletePost(deletedPost:   Post.Delete):     F[DeletionScheduled[Post]]
  def restorePost(restoredPost: Post.Restore):    F[RestoreScheduled[Post]]
  def upvotePost(vote:          Post.Upvote):     F[Unit]
  def downvotePost(vote:        Post.Downvote):   F[Unit]
  def revokePostVote(vote:      Post.RevokeVote): F[Unit]
}
