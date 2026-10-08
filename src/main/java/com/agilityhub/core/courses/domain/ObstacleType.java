package com.agilityhub.core.courses.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Values from the committed course-core v1 schema. */
public enum ObstacleType {
    @JsonProperty("Jump") JUMP,
    @JsonProperty("DoubleJump") DOUBLE_JUMP,
    @JsonProperty("Tunnel3m") TUNNEL3M,
    @JsonProperty("Tunnel4m") TUNNEL4M,
    @JsonProperty("Tunnel5m") TUNNEL5M,
    @JsonProperty("Tunnel6m") TUNNEL6M,
    @JsonProperty("DogWalk") DOG_WALK,
    @JsonProperty("AFrame") AFRAME,
    @JsonProperty("Seesaw") SEESAW,
    @JsonProperty("Weave") WEAVE,
    @JsonProperty("LongJump") LONG_JUMP,
    @JsonProperty("Wall") WALL,
    @JsonProperty("Tire") TIRE,
    @JsonProperty("Unknown") UNKNOWN
}
