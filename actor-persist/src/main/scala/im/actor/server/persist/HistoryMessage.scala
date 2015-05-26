package im.actor.server.persist

import com.github.tototoshi.slick.PostgresJodaSupport._
import org.joda.time.DateTime
import slick.dbio.Effect.{ Write, Read }
import slick.driver.PostgresDriver
import slick.driver.PostgresDriver.api._
import slick.profile.{ FixedSqlStreamingAction, FixedSqlAction }

import im.actor.server.models

class HistoryMessageTable(tag: Tag) extends Table[models.HistoryMessage](tag, "history_messages") {
  def userId = column[Int]("user_id", O.PrimaryKey)

  def peerType = column[Int]("peer_type", O.PrimaryKey)

  def peerId = column[Int]("peer_id", O.PrimaryKey)

  def date = column[DateTime]("date", O.PrimaryKey)

  def senderUserId = column[Int]("sender_user_id", O.PrimaryKey)

  def randomId = column[Long]("random_id", O.PrimaryKey)

  def messageContentHeader = column[Int]("message_content_header")

  def messageContentData = column[Array[Byte]]("message_content_data")

  def deletedAt = column[Option[DateTime]]("deleted_at")

  def * = (userId, peerType, peerId, date, senderUserId, randomId, messageContentHeader, messageContentData, deletedAt) <>
    (applyHistoryMessage.tupled, unapplyHistoryMessage)

  private def applyHistoryMessage: (Int, Int, Int, DateTime, Int, Long, Int, Array[Byte], Option[DateTime]) ⇒ models.HistoryMessage = {
    case (userId, peerType, peerId, date, senderUserId, randomId, messageContentHeader, messageContentData, deletedAt) ⇒
      models.HistoryMessage(
        userId = userId,
        peer = models.Peer(models.PeerType.fromInt(peerType), peerId),
        date = date,
        senderUserId = senderUserId,
        randomId = randomId,
        messageContentHeader = messageContentHeader,
        messageContentData = messageContentData,
        deletedAt = deletedAt
      )
  }

  private def unapplyHistoryMessage: models.HistoryMessage ⇒ Option[(Int, Int, Int, DateTime, Int, Long, Int, Array[Byte], Option[DateTime])] = { historyMessage ⇒
    models.HistoryMessage.unapply(historyMessage) map {
      case (userId, peer, date, senderUserId, randomId, messageContentHeader, messageContentData, deletedAt) ⇒
        (userId, peer.typ.toInt, peer.id, date, senderUserId, randomId, messageContentHeader, messageContentData, deletedAt)
    }
  }
}

object HistoryMessage {
  val messages = TableQuery[HistoryMessageTable]

  val notDeletedMessages = messages.filter(_.deletedAt.isEmpty)

  def create(message: models.HistoryMessage): FixedSqlAction[Int, NoStream, Write] =
    messages += message

  def create(newMessages: Seq[models.HistoryMessage]): FixedSqlAction[Option[Int], NoStream, Write] =
    messages ++= newMessages

  def find(userId: Int, peer: models.Peer, dateOpt: Option[DateTime], limit: Int): FixedSqlStreamingAction[Seq[models.HistoryMessage], models.HistoryMessage, Read] = {
    val baseQuery = notDeletedMessages
      .filter(m ⇒
        m.userId === userId &&
          m.peerType === peer.typ.toInt &&
          m.peerId === peer.id)

    val query = dateOpt match {
      case Some(date) ⇒
        baseQuery.filter(_.date <= date).sortBy(_.date.desc)
      case None ⇒
        baseQuery.sortBy(_.date.asc)
    }

    query.take(limit).result
  }

  def find(userId: Int, peer: models.Peer): FixedSqlStreamingAction[Seq[models.HistoryMessage], models.HistoryMessage, Read] =
    notDeletedMessages
      .filter(m ⇒ m.userId === userId && m.peerType === peer.typ.toInt && m.peerId === peer.id)
      .sortBy(_.date.desc)
      .result

  def updateContentAll(userIds: Set[Int], randomId: Long, peer: models.Peer,
                       messageContentHeader: Int, messageContentData: Array[Byte]): FixedSqlAction[Int, NoStream, Write] =
    notDeletedMessages
      .filter(m ⇒ m.randomId === randomId && m.peerType === peer.typ.toInt)
      .filter(m ⇒ peer.typ match {
        case models.PeerType.Group   ⇒ m.peerId === peer.id
        case models.PeerType.Private ⇒ m.peerId inSet userIds
      })
      .filter(_.userId inSet userIds)
      .map(m ⇒ (m.messageContentHeader, m.messageContentData))
      .update((messageContentHeader, messageContentData))

  def getUnreadCount(userId: Int, peer: models.Peer, lastReadAt: DateTime): FixedSqlAction[Int, PostgresDriver.api.NoStream, Read] =
    notDeletedMessages
      .filter(m ⇒ m.userId === userId && m.peerType === peer.typ.toInt && m.peerId === peer.id)
      .filter(m ⇒ m.date > lastReadAt && m.senderUserId =!= userId)
      .length
      .result

  def deleteAll(userId: Int, peer: models.Peer): FixedSqlAction[Int, NoStream, Write] =
    notDeletedMessages
      .filter(m ⇒ m.userId === userId && m.peerType === peer.typ.toInt && m.peerId === peer.id)
      .map(_.deletedAt)
      .update(Some(new DateTime))

  def delete(userId: Int, peer: models.Peer, randomIds: Set[Long]) =
    notDeletedMessages
      .filter(m ⇒ m.userId === userId && m.peerType === peer.typ.toInt && m.peerId === peer.id)
      .filter(_.randomId inSet randomIds)
      .map(_.deletedAt)
      .update(Some(new DateTime))
}