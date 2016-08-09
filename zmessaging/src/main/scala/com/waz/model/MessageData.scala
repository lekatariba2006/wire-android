/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.model

import android.database.Cursor
import android.database.DatabaseUtils.queryNumEntries
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.waz.api
import com.waz.api.{Message, ZmsVersion}
import com.waz.api.Message.Type._
import com.waz.db.Col._
import com.waz.db.Dao
import com.waz.model.AssetMetaData.HasDimensions
import com.waz.model.ConversationData.ConversationDataDao
import com.waz.model.GenericContent.{Asset, ImageAsset, Knock, LinkPreview, Location}
import com.waz.model.MessageData.MessageState
import com.waz.model.messages.media.{MediaAssetData, MediaAssetDataProtocol}
import com.waz.service.conversation.ConversationsService
import com.waz.service.media.{MessageContentBuilder, RichMediaContentParser}
import com.waz.sync.client.OpenGraphClient.OpenGraphData
import com.waz.utils.{EnumCodec, JsonDecoder, JsonEncoder, returning}
import org.json.JSONObject
import org.threeten.bp.Instant

import scala.collection.breakOut

case class MessageData(id: MessageId,
                       convId: ConvId,
                       source: EventId,
                       msgType: Message.Type,
                       userId: UserId,
                       content: Seq[MessageContent] = Seq.empty,
                       protos: Seq[GenericMessage] = Seq.empty,
                       firstMessage: Boolean = false,
                       members: Set[UserId] = Set.empty[UserId],
                       recipient: Option[UserId] = None,
                       email: Option[String] = None,
                       name: Option[String] = None,
                       state: MessageState = Message.Status.SENT,
                       time: Instant = Instant.now,
                       localTime: Instant = MessageData.UnknownInstant) {

  def getContent(index: Int) = {
    if (index == 0) content.headOption.getOrElse(MessageContent.Empty)
    else content.drop(index).headOption.getOrElse(MessageContent.Empty)
  }

  def contentString = content.map(_.content).mkString(" ")

  def assetId = AssetId(id.str)

  def isLocal = state == Message.Status.DEFAULT || state == Message.Status.PENDING || state == Message.Status.FAILED || state == Message.Status.FAILED_READ

  def hotKnock =
    msgType == Message.Type.KNOCK && protos.exists {
      case GenericMessage(_, Knock(true)) => true
      case _ => false
    }

  lazy val imageDimensions: Option[Dim2] = msgType match {
    case Message.Type.ASSET =>
      protos.collectFirst {
        case GenericMessage(_, Asset.WithDimensions(d)) => d
        case GenericMessage(_, ImageAsset(_, _, _, w, h, _, _, _, _)) => Dim2(w, h)
      } orElse {
        content.headOption.collect {
          case MessageContent(_, _, _, _, Some(_), w, h, _, _) => Dim2(w, h)
        }
      }
    case _ =>
      protos.reverseIterator.collectFirst {
        case GenericMessage(_, Asset(Some(Asset.Original(_, _, _, Some(HasDimensions(d)), _)), _, _)) => d
        case GenericMessage(_, Asset(_, Some(Asset.Preview(_, _, _, _, Some(HasDimensions(d)))), _)) => d
      }
  }

  lazy val location =
    protos.collectFirst {
      case GenericMessage(_, Location(lon, lat, descr, zoom)) => new api.MessageContent.Location(lon, lat, descr.getOrElse(""), zoom.getOrElse(14))
    }

  /**
   * System messages are messages generated by backend in response to user actions.
   * Those messages are not encrypted and don't have global message id (nonce).
   *
   */
  def isSystemMessage = msgType match {
    case RENAME | CONNECT_REQUEST | CONNECT_ACCEPTED | MEMBER_JOIN | MEMBER_LEAVE | MISSED_CALL | INCOMING_CALL => true
    case _ => false
  }

  def isAssetMessage = MessageData.IsAsset(msgType)

  def hasSameContentType(m: MessageData) = {
    msgType == m.msgType && content.zip(m.content).forall { case (c, c1) => c.tpe == c1.tpe && c.openGraph.isDefined == c1.openGraph.isDefined } // openGraph may affect message type
  }

  override def toString: String =
    if (ZmsVersion.DEBUG) super.toString
    else s"MessageData($id, $convId, $source, $msgType, $userId, $content, protos len: ${protos.length}, $state, $time)"
}

case class MessageContent(
                           tpe: Message.Part.Type,
                           content: String,
                           richMedia: Option[MediaAssetData],
                           openGraph: Option[OpenGraphData],
                           asset: Option[AssetId],
                           width: Int,
                           height: Int,
                           syncNeeded: Boolean,
                           mentions: Map[UserId, String]
                         ) {

  def contentAsUri: Uri = RichMediaContentParser.parseUriWithScheme(content)
}

object MessageContent extends ((Message.Part.Type, String, Option[MediaAssetData], Option[OpenGraphData], Option[AssetId], Int, Int, Boolean, Map[UserId, String]) => MessageContent) {
  import MediaAssetDataProtocol._

  val Empty = apply(Message.Part.Type.TEXT, "")

  def apply(tpe: Message.Part.Type, content: String, openGraph: Option[OpenGraphData] = None, asset: Option[AssetId] = None, width: Int = 0, height: Int = 0, syncNeeded: Boolean = false, mentions: Map[UserId, String] = Map.empty): MessageContent =
    MessageContent(tpe, content, emptyMediaAsset(tpe), openGraph, asset, width, height, syncNeeded, mentions)

  def emptyMediaAsset(tpe: Message.Part.Type) =
    if (tpe == Message.Part.Type.SPOTIFY || tpe == Message.Part.Type.SOUNDCLOUD || tpe == Message.Part.Type.YOUTUBE) Some(MediaAssetData.empty(tpe)) else None

  implicit lazy val Decoder: JsonDecoder[MessageContent] = new JsonDecoder[MessageContent] {
    import com.waz.utils.JsonDecoder._

    import scala.collection.JavaConverters._

    def mentionsMap(js: JSONObject): Map[UserId, String] =
      js.keys().asInstanceOf[java.util.Iterator[String]].asScala.map(key => UserId(key) -> js.getString(key)).toMap

    override def apply(implicit js: JSONObject): MessageContent = {
      val tpe = ContentTypeCodec.decode('type)
      val mentions = if (js.has("mentions") && !js.isNull("mentions")) mentionsMap(js.getJSONObject("mentions")) else Map.empty[UserId, String]
      val richMedia = opt[MediaAssetData]('richMedia) orElse { // if there's no media asset for rich media message contents, we create an expired empty one
        if (tpe == Message.Part.Type.SPOTIFY || tpe == Message.Part.Type.SOUNDCLOUD || tpe == Message.Part.Type.YOUTUBE) Some(MediaAssetData.empty(tpe)) else None
      }

      MessageContent(tpe, 'content, richMedia, opt[OpenGraphData]('openGraph), decodeOptId[AssetId]('asset), 'width, 'height, 'syncNeeded, mentions)
    }
  }

  implicit lazy val Encoder: JsonEncoder[MessageContent] = new JsonEncoder[MessageContent] {
    override def apply(v: MessageContent): JSONObject = JsonEncoder { o =>
      o.put("type", ContentTypeCodec.encode(v.tpe))
      if (v.content != "") o.put("content", v.content)
      v.richMedia foreach (m => o.put("richMedia", MediaAssetEncoder(m)))
      v.asset.foreach { id => o.put("asset", id.str) }
      v.openGraph foreach { og => o.put("openGraph", OpenGraphData.Encoder(og)) }
      if (v.width != 0) o.put("width", v.width)
      if (v.height != 0) o.put("height", v.height)
      if (v.syncNeeded) o.put("syncNeeded", v.syncNeeded)
      if (v.mentions.nonEmpty) o.put("mentions", JsonEncoder { o =>
        v.mentions foreach { case (user, name) => o.put(user.str, name) }
      })
    }
  }

  implicit lazy val ContentTypeCodec: EnumCodec[Message.Part.Type, String] = EnumCodec.injective {
    case Message.Part.Type.TEXT => "Text"
    case Message.Part.Type.TEXT_EMOJI_ONLY => "TextEmojiOnly"
    case Message.Part.Type.ASSET => "Asset"
    case Message.Part.Type.ANY_ASSET => "AnyAsset"
    case Message.Part.Type.YOUTUBE => "YouTube"
    case Message.Part.Type.SOUNDCLOUD => "SoundCloud"
    case Message.Part.Type.SPOTIFY => "Spotify"
    case Message.Part.Type.TWITTER => "Twitter"
    case Message.Part.Type.WEB_LINK => "WebLink"
    case Message.Part.Type.GOOGLE_MAPS => "GoogleMaps"
  }
}

object MessageData extends ((MessageId, ConvId, EventId, Message.Type, UserId, Seq[MessageContent], Seq[GenericMessage], Boolean, Set[UserId], Option[UserId], Option[String], Option[String], Message.Status, Instant, Instant) => MessageData) {
  val Empty = new MessageData(MessageId(""), ConvId(""), EventId.Zero, Message.Type.UNKNOWN, UserId(""))
  val UnknownInstant = Instant.EPOCH

  type MessageState = Message.Status
  import GenericMessage._

  implicit lazy val Decoder: JsonDecoder[MessageData] = new JsonDecoder[MessageData] {
    import JsonDecoder._
    override def apply(implicit js: JSONObject): MessageData =
      MessageData('id,
        decodeId[ConvId]('convId),
        'source,
        MessageTypeCodec.decode('msgType),
        'userId,
        decodeSeq[MessageContent]('content),
        decodeSeq[GenericMessage]('protos),
        'firstMessage,
        JsonDecoder.array('members)((arr, i) => UserId(arr.getString(i))).toSet,
        decodeOptId[UserId]('recipient),
        'email,
        'name,
        Message.Status.valueOf('state),
        Instant.ofEpochMilli(decodeLong('time)),
        Instant.ofEpochMilli(decodeLong('localTime)))
  }

  implicit lazy val Encoder: JsonEncoder[MessageData] = new JsonEncoder[MessageData] {
    override def apply(v: MessageData): JSONObject = JsonEncoder { o =>
      o.put("id", v.id.str)
      o.put("convId", v.convId.str)
      o.put("source", v.source.str)
      o.put("msgType", MessageTypeCodec.encode(v.msgType))
      o.put("userId", v.userId.str)
      o.put("content", JsonEncoder.arr(v.content))
      o.put("protos", JsonEncoder.arr(v.protos))
      o.put("firstMessage", v.firstMessage)
      o.put("members", JsonEncoder.arrString(v.members.toSeq.map(_.str)))
      v.recipient foreach { r => o.put("recipient", r.str) }
      v.email foreach { o.put("email", _) }
      v.name foreach { o.put("name", _) }
      o.put("state", v.state.name())
      o.put("time", v.time.toEpochMilli)
      o.put("localTime", v.localTime.toEpochMilli)
    }
  }
  
  implicit lazy val MessageTypeCodec: EnumCodec[Message.Type, String] = EnumCodec.injective {
    case Message.Type.TEXT => "Text"
    case Message.Type.TEXT_EMOJI_ONLY => "TextEmojiOnly"
    case Message.Type.ASSET => "Asset"
    case Message.Type.ANY_ASSET => "AnyAsset"
    case Message.Type.VIDEO_ASSET => "VideoAsset"
    case Message.Type.AUDIO_ASSET => "AudioAsset"
    case Message.Type.KNOCK => "Knock"
    case Message.Type.MEMBER_JOIN => "MemberJoin"
    case Message.Type.MEMBER_LEAVE => "MemberLeave"
    case Message.Type.CONNECT_REQUEST => "ConnectRequest"
    case Message.Type.CONNECT_ACCEPTED => "ConnectAccepted"
    case Message.Type.RENAME => "Rename"
    case Message.Type.MISSED_CALL => "MissedCall"
    case Message.Type.INCOMING_CALL => "IncomingCall"
    case Message.Type.RICH_MEDIA => "RichMedia"
    case Message.Type.OTR_ERROR => "OtrFailed"
    case Message.Type.OTR_IDENTITY_CHANGED => "OtrIdentityChanged"
    case Message.Type.OTR_VERIFIED => "OtrVerified"
    case Message.Type.OTR_UNVERIFIED => "OtrUnverified"
    case Message.Type.OTR_DEVICE_ADDED => "OtrDeviceAdded"
    case Message.Type.STARTED_USING_DEVICE => "StartedUsingDevice"
    case Message.Type.HISTORY_LOST => "HistoryLost"
    case Message.Type.LOCATION => "Location"
    case Message.Type.UNKNOWN => "Unknown"
    case Message.Type.RECALLED => "Recalled"
  }

  implicit object MessageDataDao extends Dao[MessageData, MessageId]  {
    import com.waz.db._

    val Id = id[MessageId]('_id, "PRIMARY KEY").apply(_.id)
    val Conv = id[ConvId]('conv_id).apply(_.convId)
    val SourceSeq = long('source_seq)(_.source.sequence)
    val SourceHex = text('source_hex)(_.source.hex)
    val Type = text[Message.Type]('msg_type, MessageTypeCodec.encode, MessageTypeCodec.decode)(_.msgType)
    val User = id[UserId]('user_id).apply(_.userId)
    val Content = jsonArray[MessageContent, Seq, Vector]('content).apply(_.content)
    val Protos = protoSeq[GenericMessage, Seq, Vector]('protos).apply(_.protos)
    val ContentSize = int('content_size)(_.content.size)
    val FirstMessage = bool('first_msg)(_.firstMessage)
    val Members = set[UserId]('members, _.mkString(","), _.split(",").filter(!_.isEmpty).map(UserId(_))(breakOut))(_.members)
    val Recipient = opt(id[UserId]('recipient))(_.recipient)
    val Email = opt(text('email))(_.email)
    val Name = opt(text('name))(_.name)
    val State = text[MessageState]('msg_state, _.name, Message.Status.valueOf)(_.state)
    val Time = timestamp('time)(_.time)
    val LocalTime = timestamp('local_time)(_.localTime)

    override val idCol = Id

    override val table = Table("Messages", Id, Conv, SourceSeq, SourceHex, Type, User, Content, Protos, Time, LocalTime, FirstMessage, Members, Recipient, Email, Name, State, ContentSize)

    override def onCreate(db: SQLiteDatabase): Unit = {
      super.onCreate(db)
      db.execSQL(s"CREATE INDEX IF NOT EXISTS Messages_conv_source_idx on Messages ( conv_id, source_seq, source_hex )")
      db.execSQL(s"CREATE INDEX IF NOT EXISTS Messages_conv_time_source_idx on Messages ( conv_id, time, source_seq, source_hex )")
    }

    override def apply(implicit cursor: Cursor): MessageData =
      MessageData(Id, Conv, new EventId(SourceSeq, SourceHex), Type, User, Content, Protos, FirstMessage, Members, Recipient, Email, Name, State, Time, LocalTime)

    def deleteForConv(id: ConvId)(implicit db: SQLiteDatabase) = delete(Conv, id)

    def deleteUpTo(id: ConvId, upTo: Instant)(implicit db: SQLiteDatabase) = db.delete(table.name, s"${Conv.name} = '${id.str}' AND ${Time.name} <= ${Time(upTo)}", null)

    def first(conv: ConvId)(implicit db: SQLiteDatabase) = single(db.query(table.name, null, s"${Conv.name} = '$conv'", null, null, null, s"${Time.name} ASC, ${SourceSeq.name} ASC, ${SourceHex.name} ASC", "1"))

    def last(conv: ConvId)(implicit db: SQLiteDatabase) = single(db.query(table.name, null, s"${Conv.name} = '$conv'", null, null, null, s"${Time.name} DESC, ${SourceSeq.name} DESC, ${SourceHex.name} DESC", "1"))

    def lastSent(conv: ConvId)(implicit db: SQLiteDatabase) = single(db.query(table.name, null, s"${Conv.name} = '$conv' AND ${State.name} = '${Message.Status.SENT.name}'", null, null, null, s"${Time.name} DESC, ${SourceSeq.name} DESC, ${SourceHex.name} DESC", "1"))

    def lastIncomingKnock(convId: ConvId, selfUser: UserId)(implicit db: SQLiteDatabase): Option[MessageData] = single(
      db.query(table.name, null, s"${Conv.name} = ? AND ${Type.name} = ? AND ${User.name} <> ?", Array(convId.toString, Type(Message.Type.KNOCK), selfUser.str), null, null, s"${SourceSeq.name} DESC, ${SourceHex.name} DESC", "1")
    )

    def lastMissedCall(convId: ConvId)(implicit db: SQLiteDatabase): Option[MessageData] = single(
      db.query(table.name, null, s"${Conv.name} = ? AND ${Type.name} = ?", Array(convId.toString, Type(Message.Type.MISSED_CALL)), null, null, s"${SourceSeq.name} DESC, ${SourceHex.name} DESC", "1")
    )

    private val MessageEntryColumns = Array(Id.name, User.name, Type.name, State.name, ContentSize.name)
    private val MessageEntryReader = new Reader[MessageEntry] {
      override def apply(implicit c: Cursor): MessageEntry = MessageEntry(Id, User, Type, State, ContentSize)
    }

    def countMessages(convId: ConvId, p: MessageEntry => Boolean)(implicit db: SQLiteDatabase): Int =
      iteratingWithReader(MessageEntryReader)(db.query(table.name, MessageEntryColumns, s"${Conv.name} = ?", Array(convId.toString), null, null, null)).acquire(_ count p)

    def findMessages(convId: ConvId, event: Seq[EventId])(implicit db: SQLiteDatabase) = {
      val eventsSet = event.toSet
      iterating(db.query(table.name, null, s"${Conv.name} = '${convId.str}' AND ${SourceSeq.name} IN (${event.map(_.sequence).mkString(",")})", null, null, null, null)).map(_ filter (m => eventsSet(m.source) ))
    }

    def countNewer(convId: ConvId, time: Instant)(implicit db: SQLiteDatabase) =
      queryNumEntries(db, table.name, s"${Conv.name} = '${convId.str}' AND ${Time.name} > ${time.toEpochMilli}")

    def countFailed(convId: ConvId)(implicit db: SQLiteDatabase) = queryNumEntries(db, table.name, s"${Conv.name} = '${convId.str}' AND ${State.name} = '${Message.Status.FAILED}'")

    def listLocalMessages(convId: ConvId)(implicit db: SQLiteDatabase) = list(db.query(table.name, null, s"${Conv.name} = '$convId' AND ${State.name} in ('${Message.Status.DEFAULT}', '${Message.Status.PENDING}', '${Message.Status.FAILED}')", null, null, null, s"${Time.name} ASC"))

    def findLocalFrom(convId: ConvId, time: Instant)(implicit db: SQLiteDatabase) =
      iterating(db.query(table.name, null, s"${Conv.name} = '$convId' AND ${State.name} in ('${Message.Status.DEFAULT}', '${Message.Status.PENDING}', '${Message.Status.FAILED}') AND ${Time.name} >= ${time.toEpochMilli}", null, null, null, s"${Time.name} ASC"))

    def findLatestUpToEvent(convId: ConvId, event: EventId)(implicit db: SQLiteDatabase) =
      single(db.query(table.name, null, s"${Conv.name} = '$convId' AND (${SourceSeq.name} < ${event.sequence} OR ${SourceSeq.name} = ${event.sequence} AND ${SourceHex.name} <= '${event.hex}')", null, null, null, s"${SourceSeq.name} DESC ,${SourceHex.name} DESC", "1"))

    def findMessages(conv: ConvId)(implicit db: SQLiteDatabase) = db.query(table.name, null, s"${Conv.name} = '$conv'", null, null, null, s"${Time.name} ASC, ${SourceSeq.name} ASC, ${SourceHex.name} ASC")

    def findMessagesFrom(conv: ConvId, time: Instant)(implicit db: SQLiteDatabase) =
      iterating(db.query(table.name, null, s"${Conv.name} = '$conv' and ${Time.name} >= ${time.toEpochMilli}", null, null, null, s"${Time.name} ASC, ${SourceSeq.name} ASC, ${SourceHex.name} ASC"))

    private val IndexColumns = Array(Id.name, Time.name, SourceSeq.name, SourceHex.name)
    def msgIndexCursor(conv: ConvId)(implicit db: SQLiteDatabase) = db.query(table.name, IndexColumns, s"${Conv.name} = '$conv'", null, null, null, s"${Time.name} ASC, ${SourceSeq.name} ASC, ${SourceHex.name} ASC")

    def countAtLeastAsOld(conv: ConvId, time: Instant)(implicit db: SQLiteDatabase) =
      queryNumEntries(db, table.name, s"""${Conv.name} = '${Conv(conv)}' AND ${Time.name} <= ${Time(time)}""")

    def countSentByType(selfUserId: UserId, tpe: Message.Type)(implicit db: SQLiteDatabase) = queryNumEntries(db, table.name, s"${User.name} = '${User(selfUserId)}' AND ${Type.name} = '${Type(tpe)}'")

    /**
     * Returns incoming messages (for all unmuted conversations) with local time greater then given time in millis.
     */
    def listIncomingMessages(selfUserId: UserId, since: Long = System.currentTimeMillis() - ConversationsService.KnockTimeout.toMillis, limit: Int = 25)(implicit db: SQLiteDatabase): Vector[MessageData] = list(db.rawQuery(
      s"""
         | SELECT msg.*
         | FROM ${table.name} msg, ${ConversationDataDao.table.name} conv
         | WHERE msg.${Conv.name} = conv.${ConversationDataDao.Id.name} AND conv.${ConversationDataDao.Muted.name} = 0
         | AND msg.${LocalTime.name} > ? AND msg.${User.name} != ?
         | ORDER BY msg.${LocalTime.name} DESC
         | LIMIT $limit""".stripMargin, Array(since.toString, selfUserId.str)
    ))
  }

  case class MessageEntry(id: MessageId, user: UserId, tpe: Message.Type = Message.Type.TEXT, state: Message.Status = Message.Status.DEFAULT, contentSize: Int = 1)

  val contentParser = new RichMediaContentParser

  def messageContent(message: String, mentions: Map[UserId, String] = Map.empty, links: Seq[LinkPreview] = Nil, weblinkEnabled: Boolean = false): (Message.Type, Seq[MessageContent]) =
    if (message.trim.isEmpty) (Message.Type.TEXT, textContent(message))
    else if (links.isEmpty) {
      val ct = contentParser.splitContent(message, weblinkEnabled)

      (ct.size, ct.head.tpe) match {
        case (1, Message.Part.Type.TEXT) => (Message.Type.TEXT, applyMentions(ct, mentions))
        case (1, Message.Part.Type.TEXT_EMOJI_ONLY) => (Message.Type.TEXT_EMOJI_ONLY, applyMentions(ct, mentions))
        case _ => (Message.Type.RICH_MEDIA, applyMentions(ct, mentions))
      }
    } else {
      // apply links
      def linkEnd(offset: Int) = {
        val end = message.indexWhere(_.isWhitespace, offset + 1)
        if (end < 0) message.length else end
      }

      val res = new MessageContentBuilder

      val end = links.sortBy(_.urlOffset).foldLeft(0) { case (prevEnd, link) =>
        if (link.urlOffset > prevEnd) res ++= contentParser.splitContent(message.substring(prevEnd, link.urlOffset))

        returning(linkEnd(link.urlOffset)) { end =>
          if (end > link.urlOffset) {
            val openGraph = Option(link.getArticle).map { a => OpenGraphData(a.title, a.summary, None, "", Option(a.permanentUrl).filter(_.nonEmpty).map(Uri.parse)) }
            res += MessageContent(Message.Part.Type.WEB_LINK, message.substring(link.urlOffset, end), openGraph)
          }
        }
      }

      if (end < message.length) res ++= contentParser.splitContent(message.substring(end))

      (Message.Type.RICH_MEDIA, applyMentions(res.result(), mentions))
    }


  def textContent(message: String): Seq[MessageContent] = Seq(RichMediaContentParser.textMessageContent(message))

  object IsAsset {
    def apply(tpe: Message.Type): Boolean = unapply(tpe)
    def unapply(tpe: Message.Type): Boolean = tpe match {
      case ANY_ASSET | VIDEO_ASSET | AUDIO_ASSET => true
      case _ => false
    }
  }

  private def applyMentions(content: Seq[MessageContent], mentions: Map[UserId, String]) =
    if (mentions.isEmpty) content
    else if (content.size == 1) content.map(_.copy(mentions = mentions))
    else content map { ct =>
      val ms = mentions.filter { case (id, name) => ct.content.contains(s"@$name") }
      if (ms.isEmpty) ct else ct.copy(mentions = ms)
    }
}
