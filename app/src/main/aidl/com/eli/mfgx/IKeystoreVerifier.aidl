package com.eli.mfgx;

interface IKeystoreVerifier {
    byte[] sign(in byte[] challenge);
}
