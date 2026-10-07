package com.travelrisk.platform.brief;

/** Writes a brief from a trip's facts with a language model. */
public interface TripBriefWriter {
  /** False when the writer has no credentials, so the template is used without trying. */
  boolean configured();

  /** The model named on briefs this writer produces. */
  String model();

  /** The brief text; throws when the model gives no usable answer. */
  String write(TripBriefFacts facts) throws Exception;
}
