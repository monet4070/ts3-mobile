package io.github.ts3mobile.protocol

/** The request did not cross the command submission boundary. */
class ChannelMoveNotSubmittedException : IllegalStateException("Channel move was not submitted")

/** The transport may own the request; only the server can confirm the move. */
class ChannelMoveUnconfirmedException : IllegalStateException("Channel move was not confirmed")
