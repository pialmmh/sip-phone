#include "rtp_transport.h"
#include <stddef.h>

/* Forward declarations for backend ops getters */
extern const RtpTransportOps* rtp_transport_simple_get_ops(void);

/* oRTP backend — compiled conditionally */
#ifdef HAVE_ORTP
extern const RtpTransportOps* rtp_transport_ortp_get_ops(void);
#endif

/* pjmedia backend — future */
#ifdef HAVE_PJMEDIA
extern const RtpTransportOps* rtp_transport_pjmedia_get_ops(void);
#endif

const RtpTransportOps* rtp_transport_get_ops(RtpTransportType type) {
    switch (type) {
        case RTP_TRANSPORT_SIMPLE:
            return rtp_transport_simple_get_ops();
#ifdef HAVE_ORTP
        case RTP_TRANSPORT_ORTP:
            return rtp_transport_ortp_get_ops();
#endif
#ifdef HAVE_PJMEDIA
        case RTP_TRANSPORT_PJMEDIA:
            return rtp_transport_pjmedia_get_ops();
#endif
        default:
            return NULL;
    }
}

static const RtpTransportOps* available_backends[4];

const RtpTransportOps** rtp_transport_list_available(void) {
    int idx = 0;
    available_backends[idx++] = rtp_transport_simple_get_ops();
#ifdef HAVE_ORTP
    available_backends[idx++] = rtp_transport_ortp_get_ops();
#endif
#ifdef HAVE_PJMEDIA
    available_backends[idx++] = rtp_transport_pjmedia_get_ops();
#endif
    available_backends[idx] = NULL;
    return available_backends;
}
