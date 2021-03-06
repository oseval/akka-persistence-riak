package akka.persistence.journal.riak

import akka.actor.ActorLogging
import akka.serialization.SerializationExtension
import scala.concurrent.Future
import scala.collection.immutable.Seq
import akka.persistence.PersistentRepr
import akka.persistence.journal.AsyncWriteJournal
import akka.persistence.riak._
import akka.persistence.riak.Implicits._
import scala.concurrent.ExecutionContext.Implicits.global // TODO make available on outside
import scala.collection.JavaConverters._

class RiakAsyncWriteJournal extends AsyncWriteJournal with ActorLogging {

  private val root = "akka-persistence-riak-async"
  private lazy val config = context.system.settings.config
  implicit lazy val serialization = SerializationExtension(context.system)
  implicit lazy val bucketType: JournalBucketType = JournalBucketType(config getString (s"$root.journal.bucket-type", "journal"))

  private lazy val nodes = (config getStringList s"$root.nodes").asScala.toList
  private lazy val riak: Riak = Riak(nodes, 1, 50)

  context.system.registerOnTermination(riak shutdown ())

  /**
   * asynchronously writes a batch of persistent messages to the riak journal.
   */
  override def asyncWriteMessages(messages: Seq[PersistentRepr]): Future[Unit] = for {
    seqNrs <- riak storeMessages messages
    res <- riak storeSeqNrs seqNrs // TODO make the write atomic
  } yield ()

  /**
   * asynchronously deletes all persistent messages up to `toSequenceNr` (inclusive)
   * from the riak journal. If `permanent` is set to `false`, the persistent messages are marked
   * as deleted, otherwise they are permanently deleted.
   */
  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long, permanent: Boolean): Future[Unit] = {
    val pId = PersistId(persistenceId)
    for {
      sNrs <- riak fetchSequenceNrs (pId, _ <= SeqNr(toSequenceNr))
      _ <- delete(pId, sNrs, if (permanent) Permanent else Temporary)
    } yield ()
  }

  private def delete(pId: PersistId, sNrs: Seq[SeqNr], mode: DeleteMode) = Future.sequence {
    sNrs map (sNr => riak delete (pId, sNr, mode))
  }

  /**
   * asynchronously reads the highest stored sequence number for the
   * given `persistenceId`.from the riak journal
   */
  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = for {
    seqNrs <- riak fetchSequenceNrs PersistId(persistenceId)
  } yield if (seqNrs.isEmpty) 0L else seqNrs.maxBy(_.value).value

  /**
   * asynchronously replays persistent messages. Implementations replay
   * a message by calling `replayCallback`. The returned future must be completed
   * when all messages (matching the sequence number bounds) have been replayed.
   * The future must be completed with a failure if any of the persistent messages
   * could not be replayed.
   *
   * The `replayCallback` must also be called with messages that have been marked
   * as deleted. In this case a replayed message's `deleted` method must return
   * `true`.
   *
   * The channel ids of delivery confirmations that are available for a replayed
   * message must be contained in that message's `confirms` sequence.
   */
  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(replayCallback: (PersistentRepr) => Unit): Future[Unit] = {

    def isInRange(seqNr: SeqNr) = seqNr.value >= fromSequenceNr && seqNr.value <= toSequenceNr

    def replayCallbacks(seqNrs: Seq[SeqNr]): Future[Unit] = seqNrs.foldLeft(Future.successful(())) {
      (acc, elem) =>
        for {
          _ <- acc
          msg <- riak fetchMessage (PersistId(persistenceId), elem)
        } yield msg foreach replayCallback
    }

    for {
      seqNrs <- riak fetchSequenceNrs (PersistId(persistenceId), isInRange, Some(SeqNr(max)))
      msg <- replayCallbacks(seqNrs)
    } yield ()
  }

  // ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  //                                                                                                                  //
  // DEPRECATED STUFF BEYOND THIS POINT                                                                               //
  //                                                                                                                  //
  // ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  import akka.persistence.{ PersistentConfirmation, PersistentId }

  @deprecated("writeConfirmations will be removed, since Channels will be removed.", since = "0.1")
  override def asyncWriteConfirmations(confirmations: Seq[PersistentConfirmation]): Future[Unit] =
    riak writeConfirmations confirmations

  @deprecated("asyncDeleteMessages will be removed.", since = "0.1")
  override def asyncDeleteMessages(messageIds: Seq[PersistentId], permanent: Boolean): Future[Unit] = Future.sequence {
    messageIds.map(mId => { delete(PersistId(mId.persistenceId), Seq(SeqNr(mId.sequenceNr)), if (permanent) Permanent else Temporary) })
  }.map(_ => ())
}
