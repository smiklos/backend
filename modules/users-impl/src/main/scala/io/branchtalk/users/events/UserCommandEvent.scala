package io.branchtalk.users.events

import com.sksamuel.avro4s._
import io.branchtalk.ADT
import io.branchtalk.logging.CorrelationID
import io.branchtalk.shared.model._
import io.branchtalk.shared.model.AvroSupport._
import io.branchtalk.users.model.{ Password, Permission, Session, User }
import io.scalaland.catnip.Semi

@Semi(Decoder, Encoder, FastEq, ShowPretty, SchemaFor) sealed trait UserCommandEvent extends ADT
object UserCommandEvent {

  @Semi(Decoder, Encoder, FastEq, ShowPretty, SchemaFor) final case class Create(
    id:               ID[User],
    email:            User.Email,
    username:         User.Name,
    description:      Option[User.Description],
    password:         Password,
    createdAt:        CreationTime,
    sessionID:        ID[Session],
    sessionExpiresAt: Session.ExpirationTime,
    correlationID:    CorrelationID
  ) extends UserCommandEvent

  @Semi(Decoder, Encoder, FastEq, ShowPretty, SchemaFor) final case class Update(
    id:                ID[User],
    moderatorID:       Option[ID[User]],
    newUsername:       Updatable[User.Name],
    newDescription:    OptionUpdatable[User.Description],
    newPassword:       Updatable[Password],
    updatePermissions: List[Permission.Update],
    modifiedAt:        ModificationTime,
    correlationID:     CorrelationID
  ) extends UserCommandEvent

  @Semi(Decoder, Encoder, FastEq, ShowPretty, SchemaFor) final case class Delete(
    id:            ID[User],
    moderatorID:   Option[ID[User]],
    deletedAt:     ModificationTime,
    correlationID: CorrelationID
  ) extends UserCommandEvent
}
