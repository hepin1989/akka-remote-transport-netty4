package qgame.akka.remote.transport.netty4.tcp

import java.net.InetSocketAddress

import akka.actor._
import akka.remote.transport.AssociationHandle
import akka.remote.transport.AssociationHandle.HandleEventListener
import akka.util.ByteString
import io.netty.channel.Channel

import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.util.{ Failure, Success }

/**
 * Created by kerr.
 */
class Netty4TcpTransportAssociator(remoteAddress: Address, flushDuration: FiniteDuration, channel: Channel, autoFlush: Boolean, op: AssociationHandle => Any) extends Actor with ActorLogging {
  require(channel.isActive, "channel must be active")
  require(channel.localAddress() ne null, "channel local address not null")
  require(channel.remoteAddress() ne null, "channel remote address not null")
  private var flushTickTask: Cancellable = _
  override def receive: Receive = {
    case AssociateChannelInBound =>
      //first register the associator
      log.debug("associate channel inbound/connected in ,fireUserEventTriggered RegisterAssociator at :{}", self)
      channel.pipeline().fireUserEventTriggered(RegisterAssociator(self))
      log.debug("register associator command fired,waiting ack at:{}", self)
      context.become(waitRegisterAssociatorACK())
    case AssociateChannelOutBound =>
      //first register the associator
      log.debug("associate channel outbound/connected out ,fireUserEventTriggered RegisterAssociator at :{}", self)
      channel.pipeline().fireUserEventTriggered(RegisterAssociator(self))
      log.debug("register associator command fired,waiting ack at:{}", self)
      context.become(waitRegisterAssociatorACK())
  }

  private def waitRegisterAssociatorACK(): Actor.Receive = {
    case RegisterAssociatorACK =>
      //going to notify inbound association ,and waiting the
      //read register
      log.debug("register associator success at:{}", self)
      val associationHandler = Netty4TcpTransportAssociationHandle(channel, autoFlush)
      log.debug(
        s"""
          |~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
          |           associationHandler information
          |~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
          |channel:${associationHandler.channel}
          |localAddress:${associationHandler.localAddress}
          |remoteAddress:${associationHandler.remoteAddress}
          |-------------------------------------------------------
          """.stripMargin
      )

      val readHandlerRegisterFuture = associationHandler.readHandlerPromise.future
      import scala.concurrent.ExecutionContext.Implicits.global
      log.debug("register call back on readHandlerRegisterFuture at :{}", self)
      readHandlerRegisterFuture.onComplete {
        case Success(handleEventListener) =>
          log.debug("readHandlerRegisterFuture success,tell self at :{}", self)
          self ! HandleEventListenerRegisterSuccess(handleEventListener)
        case Failure(exception) =>
          log.error("readHandlerRegisterFuture failed,tell self at :{}", self)
          self ! HandleEventListenerRegisterFailure(exception)
      }
      log.debug("associationHandler created,doing op at :{}", self)
      op(associationHandler)
      log.debug("associationHandler handled,waiting HandleEventListenerRegisterACK at:{}", self)
      context.become(waitHandleEventListenerRegisterACK())
  }

  private def waitHandleEventListenerRegisterACK(): Actor.Receive = {
    case HandleEventListenerRegisterSuccess(handleEventListener) =>
      log.debug("handle event lister register success,firing RegisterHandlerEventListener at :{}", self)
      //now should register the handleEventListener to the channel
      //via trigger user defined event
      channel.pipeline().fireUserEventTriggered(RegisterHandlerEventListener(handleEventListener))
      log.debug("RegisterHandlerEventListener fired,waiting ack at :{}", self)
      context.become(waitingRegisterHandlerEventListenerACK())
    case HandleEventListenerRegisterFailure(exception) =>
      log.error(exception, "handle event listener register error for channel :{} at :{}", channel, self)
      import qgame.akka.remote.transport.netty4.tcp.Netty4TcpTransport._

      import scala.concurrent.ExecutionContext.Implicits.global
      channel.disconnect().onComplete {
        case Success(underlyingChannel) =>
          underlyingChannel.close()
        case Failure(e) =>
          log.error(e, "error occur when disconnect channel at :{}", self)
          channel.close()
      }
  }

  private def waitingRegisterHandlerEventListenerACK(): Actor.Receive = {
    case RegisterHandlerEventListenerACK =>
      log.debug("register handle event listener success :{}", self)
      channel.config().setAutoRead(true)
      log.debug("becoming associated at :{}", self)
      if (!autoFlush) {
        log.debug("going to schedule the flush tick ,duration :{} at :{}", flushDuration, self)
        //flushTickTask = context.system.scheduler.schedule(flushDuration/7,flushDuration,self,FlushTick)
        import context.dispatcher
        flushTickTask = context.system.scheduler.schedule(flushDuration / 7, flushDuration) {
          if (channel.isActive) {
            channel.flush()
          }
        }
      }
      context.parent ! Associated(remoteAddress)
      context.become(associated())
  }

  private def associated(): Actor.Receive = {
    case FlushTick =>
      if (channel.isActive) {
        channel.flush()
      }
    case ChannelInActive(underlyingChannel) =>
      //channel is broken
      log.debug("channel inactive ,current associated, channel:{} at:{}", underlyingChannel, self)
      context.parent ! DeAssociated(remoteAddress)
      context.stop(self)
    case ChannelExceptionCaught(underlyingChannel, exception) =>
      //channel exception
      log.error(exception, "channel inactive ,current associated, channel:{} at:{}", underlyingChannel, self)
    case RequestShutdown(duplicated) =>
      log.debug("request shutdown ,shutdown channel :{},at :{}", channel, self)
      channel.flush()
      val replyTo = sender()
      Netty4TcpTransport.closeChannelGracefully(channel) {
        closedChannel =>
          replyTo ! AssociatorShutdownACK
      }
      if (duplicated) {
        context.become(waitingDuplicatedShutDown())
      }
  }

  private def waitingDuplicatedShutDown(): Actor.Receive = {
    case ChannelInActive(underlyingChannel) =>
      //channel is broken
      log.debug("channel inactive ,current associated, channel:{} at:{}", underlyingChannel, self)
      context.stop(self)
  }

  @throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    if (flushTickTask ne null) {
      flushTickTask.cancel()
    }
    super.postStop()
  }
}

sealed trait AssociatorCommand extends NoSerializationVerificationNeeded

case object AssociateChannelInBound extends AssociatorCommand

case object AssociateChannelOutBound extends AssociatorCommand

case class RequestShutdown(duplicated: Boolean) extends AssociatorCommand

case object FlushTick extends AssociatorCommand

case class Associated(remoteAddress: Address) extends NoSerializationVerificationNeeded

case class DeAssociated(remoteAddress: Address) extends NoSerializationVerificationNeeded

case object AssociatorShutdownACK extends NoSerializationVerificationNeeded

sealed trait HandleEventListenerRegisterACK extends NoSerializationVerificationNeeded

case class HandleEventListenerRegisterSuccess(handleEventListener: HandleEventListener) extends HandleEventListenerRegisterACK

case class HandleEventListenerRegisterFailure(exception: Throwable) extends HandleEventListenerRegisterACK

case class Netty4TcpTransportAssociationHandle(channel: Channel, autoFlush: Boolean, localAddress: Address, remoteAddress: Address) extends AssociationHandle {
  private val innerReadHandlerPromise = Promise[HandleEventListener]()
  private val allocator = channel.alloc()
  override def disassociate(): Unit = {
    if (channel.isActive) {
      channel.flush()
    }
    Netty4TcpTransport.closeChannelGracefully(channel) {
      closedChannel =>
      //NOOP
    }
  }

  override def write(payload: ByteString): Boolean = {
    if (channel.isWritable && channel.isOpen) {
      if (autoFlush) {
        channel.writeAndFlush(allocator.buffer(payload.size).writeBytes(payload.asByteBuffer))
      } else {
        channel.write(allocator.buffer(payload.size).writeBytes(payload.asByteBuffer))
      }
      true
    } else {
      channel.flush()
      false
    }
  }
  //FIXME BUG here,using inner filed instead,this is my first fuck bug!!!
  override def readHandlerPromise: Promise[HandleEventListener] = innerReadHandlerPromise
}

object Netty4TcpTransportAssociationHandle {
  def apply(channel: Channel, autoFlush: Boolean): Netty4TcpTransportAssociationHandle = {
    this(
      channel,
      autoFlush,
      Netty4TcpTransport.inetAddressToActorAddress(channel.localAddress().asInstanceOf[InetSocketAddress]),
      Netty4TcpTransport.inetAddressToActorAddress(channel.remoteAddress().asInstanceOf[InetSocketAddress])
    )
  }
}
