package com.ainative.im;

public record ImSendCommand(
        String provider,
        String externalUserId,
        String title,
        String markdown) {
}
