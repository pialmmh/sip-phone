#ifdef HAVE_PJMEDIA

#include "ice_transport.h"
#include <pj/os.h>
#include <pj/pool.h>
#include <pj/sock.h>
#include <pj/string.h>
#include <pj/log.h>
#include <pjnath/stun_config.h>
#include <pjnath/ice_strans.h>
#include <pjnath/stun_sock.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define LOG_TAG "IceTransport"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Use global pjlib init from rtp_transport_pjmedia.c */
extern int g_pj_initialized;
extern pj_caching_pool g_cp;

#define MAX_CANDIDATES 16

struct IceSession {
    pj_pool_t* pool;
    pj_stun_config stun_cfg;
    pj_ice_strans* ice_st;
    pj_timer_heap_t* timer_heap;
    pj_ioqueue_t* ioqueue;

    /* Gathered candidates */
    IceCandidate candidates[MAX_CANDIDATES];
    int candidate_count;

    /* Credentials */
    char ufrag[32];
    char pwd[64];

    /* Best public address (from STUN) */
    char public_addr[64];
    int public_port;

    /* Local fallback */
    int local_port;

    volatile int gathering_done;
};

static void on_ice_complete(pj_ice_strans* ice_st,
                            pj_ice_strans_op op,
                            pj_status_t status) {
    IceSession* session = (IceSession*)pj_ice_strans_get_user_data(ice_st);
    if (!session) return;

    if (op == PJ_ICE_STRANS_OP_INIT) {
        if (status == PJ_SUCCESS) {
            LOGD("ICE candidate gathering complete");

            /* Extract candidates */
            unsigned count = pj_ice_strans_get_cands_count(ice_st, 1);
            if (count > MAX_CANDIDATES) count = MAX_CANDIDATES;

            const pj_ice_sess_cand* cands = NULL;
            unsigned actual = 0;
            pj_ice_strans_enum_cands(ice_st, 1, &actual, NULL);

            pj_ice_sess_cand cand_buf[MAX_CANDIDATES];
            actual = count;
            pj_ice_strans_enum_cands(ice_st, 1, &actual, cand_buf);

            session->candidate_count = 0;
            for (unsigned i = 0; i < actual; i++) {
                IceCandidate* c = &session->candidates[session->candidate_count];
                memset(c, 0, sizeof(IceCandidate));

                pj_sockaddr_print(&cand_buf[i].addr, c->address, sizeof(c->address), 0);
                c->port = pj_sockaddr_get_port(&cand_buf[i].addr);
                c->component = cand_buf[i].comp_id;
                c->priority = cand_buf[i].prio;
                strcpy(c->transport, "udp");
                snprintf(c->foundation, sizeof(c->foundation), "%.*s",
                         (int)cand_buf[i].foundation.slen, cand_buf[i].foundation.ptr);

                switch (cand_buf[i].type) {
                    case PJ_ICE_CAND_TYPE_HOST:
                        strcpy(c->type, "host");
                        break;
                    case PJ_ICE_CAND_TYPE_SRFLX:
                        strcpy(c->type, "srflx");
                        /* This is our public address */
                        strncpy(session->public_addr, c->address, sizeof(session->public_addr) - 1);
                        session->public_port = c->port;
                        break;
                    case PJ_ICE_CAND_TYPE_RELAYED:
                        strcpy(c->type, "relay");
                        break;
                    default:
                        strcpy(c->type, "host");
                        break;
                }

                if (cand_buf[i].rel_addr.addr.sa_family != 0) {
                    pj_sockaddr_print(&cand_buf[i].rel_addr, c->rel_addr, sizeof(c->rel_addr), 0);
                    c->rel_port = pj_sockaddr_get_port(&cand_buf[i].rel_addr);
                }

                LOGD("  Candidate %d: %s %s:%d type=%s prio=%d",
                     i, c->foundation, c->address, c->port, c->type, c->priority);

                session->candidate_count++;
            }

            /* Get ICE credentials */
            pj_str_t loc_ufrag, loc_pwd;
            pj_ice_strans_get_ufrag_pwd(ice_st, &loc_ufrag, &loc_pwd, NULL, NULL);
            snprintf(session->ufrag, sizeof(session->ufrag), "%.*s",
                     (int)loc_ufrag.slen, loc_ufrag.ptr);
            snprintf(session->pwd, sizeof(session->pwd), "%.*s",
                     (int)loc_pwd.slen, loc_pwd.ptr);

        } else {
            LOGE("ICE candidate gathering failed: %d", status);
        }
        session->gathering_done = 1;
    }
}

static void on_rx_data(pj_ice_strans* ice_st, unsigned comp_id,
                       void* pkt, pj_size_t size,
                       const pj_sockaddr_t* src_addr, unsigned src_addr_len) {
    (void)ice_st; (void)comp_id; (void)pkt; (void)size;
    (void)src_addr; (void)src_addr_len;
    /* RTP data received via ICE — not used for now, we use our own UDP sockets */
}

IceSession* ice_session_create(const IceSessionConfig* config) {
    if (!g_pj_initialized) {
        LOGE("pjlib not initialized");
        return NULL;
    }

    /* Register thread */
    if (!pj_thread_is_registered()) {
        pj_thread_desc* desc = calloc(1, sizeof(pj_thread_desc));
        pj_thread_t* thread;
        pj_thread_register("ice-session", *desc, &thread);
    }

    IceSession* s = calloc(1, sizeof(IceSession));
    if (!s) return NULL;

    s->local_port = config->local_rtp_port;
    s->pool = pj_pool_create(&g_cp.factory, "ice", 4096, 4096, NULL);

    /* Create timer heap and ioqueue for ICE */
    pj_timer_heap_create(s->pool, 100, &s->timer_heap);
    pj_ioqueue_create(s->pool, 16, &s->ioqueue);

    /* STUN config */
    pj_stun_config_init(&s->stun_cfg, &g_cp.factory, 0, s->ioqueue, s->timer_heap);

    /* ICE stream transport config */
    pj_ice_strans_cfg ice_cfg;
    pj_ice_strans_cfg_default(&ice_cfg);
    ice_cfg.stun_cfg = s->stun_cfg;

    /* STUN server */
    if (config->stun_server && config->stun_server[0]) {
        ice_cfg.stun.server = pj_str((char*)config->stun_server);
        ice_cfg.stun.port = (pj_uint16_t)config->stun_port;
        LOGD("STUN server: %s:%d", config->stun_server, config->stun_port);
    }

    /* TURN server */
    if (config->turn_enabled && config->turn_server && config->turn_server[0]) {
        ice_cfg.turn.server = pj_str((char*)config->turn_server);
        ice_cfg.turn.port = (pj_uint16_t)config->turn_port;
        ice_cfg.turn.auth_cred.type = PJ_STUN_AUTH_CRED_STATIC;
        ice_cfg.turn.auth_cred.data.static_cred.username = pj_str((char*)config->turn_username);
        ice_cfg.turn.auth_cred.data.static_cred.data = pj_str((char*)config->turn_password);
        ice_cfg.turn.auth_cred.data.static_cred.data_type = PJ_STUN_PASSWD_PLAIN;
        ice_cfg.turn.conn_type = PJ_TURN_TP_UDP;
        LOGD("TURN server: %s:%d", config->turn_server, config->turn_port);
    }

    /* ICE callbacks */
    pj_ice_strans_cb ice_cb;
    pj_bzero(&ice_cb, sizeof(ice_cb));
    ice_cb.on_ice_complete = on_ice_complete;
    ice_cb.on_rx_data = on_rx_data;

    /* Create ICE stream transport (1 component = RTP only) */
    pj_status_t status = pj_ice_strans_create("sipphone", &ice_cfg, 1,
                                               s, &ice_cb, &s->ice_st);
    if (status != PJ_SUCCESS) {
        LOGE("pj_ice_strans_create failed: %d", status);
        pj_pool_release(s->pool);
        free(s);
        return NULL;
    }

    LOGD("ICE session created, gathering candidates...");

    /* Poll for gathering to complete (max 5 seconds) */
    for (int i = 0; i < 500 && !s->gathering_done; i++) {
        pj_time_val tv = {0, 10}; /* 10ms */
        pj_ioqueue_poll(s->ioqueue, &tv);
        pj_timer_heap_poll(s->timer_heap, NULL);
    }

    if (!s->gathering_done) {
        LOGE("ICE gathering timeout");
    }

    /* If no STUN reflexive found, use local address */
    if (s->public_addr[0] == '\0') {
        LOGD("No STUN reflexive address, using local");
    }

    LOGD("ICE gathering done: %d candidates, public=%s:%d",
         s->candidate_count,
         s->public_addr[0] ? s->public_addr : "none",
         s->public_port);

    return s;
}

int ice_session_get_candidate_count(const IceSession* session) {
    return session ? session->candidate_count : 0;
}

int ice_session_get_candidate(const IceSession* session, int index, IceCandidate* out) {
    if (!session || !out || index < 0 || index >= session->candidate_count) return -1;
    *out = session->candidates[index];
    return 0;
}

int ice_session_get_credentials(const IceSession* session,
                                 char* ufrag, int ufrag_len,
                                 char* pwd, int pwd_len) {
    if (!session) return -1;
    if (ufrag) snprintf(ufrag, ufrag_len, "%s", session->ufrag);
    if (pwd) snprintf(pwd, pwd_len, "%s", session->pwd);
    return 0;
}

int ice_session_get_public_addr(const IceSession* session,
                                 char* addr, int addr_len, int* port) {
    if (!session) return -1;
    if (session->public_addr[0]) {
        if (addr) snprintf(addr, addr_len, "%s", session->public_addr);
        if (port) *port = session->public_port;
        return 0;
    }
    return -1; /* No STUN address available */
}

void ice_session_destroy(IceSession* session) {
    if (!session) return;
    if (session->ice_st) pj_ice_strans_destroy(session->ice_st);
    if (session->pool) pj_pool_release(session->pool);
    free(session);
}

#endif /* HAVE_PJMEDIA */
