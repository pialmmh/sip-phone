#ifndef SIPPHONE_ICE_TRANSPORT_H
#define SIPPHONE_ICE_TRANSPORT_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * ICE candidate for SDP.
 */
typedef struct {
    char foundation[32];
    int component;          /* 1=RTP, 2=RTCP */
    char transport[8];      /* "udp" */
    int priority;
    char address[64];       /* IP address */
    int port;
    char type[16];          /* "host", "srflx", "relay" */
    char rel_addr[64];      /* Related address (for srflx/relay) */
    int rel_port;
} IceCandidate;

/**
 * ICE session configuration.
 */
typedef struct {
    const char* stun_server;
    int stun_port;
    const char* turn_server;
    int turn_port;
    const char* turn_username;
    const char* turn_password;
    int turn_enabled;
    int local_rtp_port;
} IceSessionConfig;

/**
 * ICE session handle.
 */
typedef struct IceSession IceSession;

/**
 * Create ICE session and gather candidates.
 * @return session handle or NULL on failure
 */
IceSession* ice_session_create(const IceSessionConfig* config);

/**
 * Get number of gathered candidates.
 */
int ice_session_get_candidate_count(const IceSession* session);

/**
 * Get a candidate by index.
 */
int ice_session_get_candidate(const IceSession* session, int index, IceCandidate* out);

/**
 * Get ICE credentials (ufrag + pwd) for SDP.
 */
int ice_session_get_credentials(const IceSession* session,
                                 char* ufrag, int ufrag_len,
                                 char* pwd, int pwd_len);

/**
 * Get the best local address discovered (STUN reflexive or host).
 * Used for SDP c= line.
 */
int ice_session_get_public_addr(const IceSession* session,
                                 char* addr, int addr_len, int* port);

/**
 * Destroy ICE session.
 */
void ice_session_destroy(IceSession* session);

#ifdef __cplusplus
}
#endif

#endif /* SIPPHONE_ICE_TRANSPORT_H */
