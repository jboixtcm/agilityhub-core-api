FROM ghcr.io/jboixtcm/agilityhub-core-api:e11-t12
USER root
RUN rm -f /usr/bin/pebble
USER agilityhub
