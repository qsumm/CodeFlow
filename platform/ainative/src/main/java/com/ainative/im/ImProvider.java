package com.ainative.im;

public interface ImProvider {
    String provider();

    void send(ImSendCommand command);
}
