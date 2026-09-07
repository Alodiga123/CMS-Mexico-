package bank.cardissuing.transaction.application;

import bank.cardissuing.transaction.domain.ResponseCode;

/** A policy said no: the ISO code to answer with and the reason, for the log and the console. */
public record Breach(ResponseCode code, String detail) { }
