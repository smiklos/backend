package io.branchtalk.discussions.events

import com.sksamuel.avro4s._
import io.branchtalk.ADT
import io.branchtalk.shared.models._
import io.branchtalk.shared.models.AvroSupport._
import io.scalaland.catnip.Semi

@Semi(Decoder, Encoder, FastEq, ShowPretty, SchemaFor) sealed trait DiscussionEvent extends ADT
object DiscussionEvent {

  @Semi(Decoder, Encoder, FastEq, ShowPretty, SchemaFor)
  final case class ForChannel(channel: ChannelEvent) extends DiscussionEvent

  @Semi(Decoder, Encoder, FastEq, ShowPretty, SchemaFor)
  final case class ForComment(comment: CommentEvent) extends DiscussionEvent

  @Semi(FastEq, ShowPretty, SchemaFor)
  final case class ForPost(post: PostEvent) extends DiscussionEvent
}
