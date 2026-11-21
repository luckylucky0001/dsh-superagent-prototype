package dev.fellowloop.daemon;

import dev.fellowloop.protocol.Wire;

/** The daemon's outbound channel to the server. */
interface Link {
  /** Sends if connected; returns false when the frame could not be sent. */
  boolean send(Wire.Frame frame);

  boolean connected();
}
