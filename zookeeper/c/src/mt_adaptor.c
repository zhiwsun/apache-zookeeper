/*
 * Copyright 2008, Yahoo! Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#ifndef THREADED
#define THREADED
#endif

#ifndef DLL_EXPORT
#  define USE_STATIC_LIB
#endif

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include "zk_adaptor.h"
#include <stdlib.h>
#include <stdio.h>
#include <time.h>
#include <sys/time.h>
#include <signal.h>
#include <sys/select.h>
#include <errno.h>

#include <fcntl.h>

void lock_buffer_list(buffer_head_t *l)
{
    pthread_mutex_lock(&l->lock);
}
void unlock_buffer_list(buffer_head_t *l)
{
    pthread_mutex_unlock(&l->lock);
}
void lock_completion_list(completion_head_t *l)
{
    pthread_mutex_lock(&l->lock);
}
void unlock_completion_list(completion_head_t *l)
{
    pthread_cond_broadcast(&l->cond);
    pthread_mutex_unlock(&l->lock);
}
struct sync_completion *alloc_sync_completion(void)
{
    struct sync_completion *sc = (struct sync_completion*)calloc(1, sizeof(struct sync_completion));
    if (sc) {
       pthread_cond_init(&sc->cond, 0);
       pthread_mutex_init(&sc->lock, 0);
    }
    return sc;
}
int wait_sync_completion(struct sync_completion *sc)
{
    pthread_mutex_lock(&sc->lock);
    while (!sc->complete) {
        pthread_cond_wait(&sc->cond, &sc->lock);
    }
    pthread_mutex_unlock(&sc->lock);
    return 0;
}

void free_sync_completion(struct sync_completion *sc)
{
    if (sc) {
        pthread_mutex_destroy(&sc->lock);
        pthread_cond_destroy(&sc->cond);
        free(sc);
    }
}

void notify_sync_completion(struct sync_completion *sc)
{
    pthread_mutex_lock(&sc->lock);
    sc->complete = 1;
    pthread_cond_broadcast(&sc->cond);
    pthread_mutex_unlock(&sc->lock);
}

int process_async(int outstanding_sync)
{
    return 0;
}
struct adaptor_threads {
    pthread_t io;
    pthread_t completion;
    int self_pipe[2];
};

void *do_io(void *);
void *do_completion(void *);

static int set_nonblock(int fd){
    long l = fcntl(fd, F_GETFL);
    if(l & O_NONBLOCK) return 0;
    return fcntl(fd, F_SETFL, l | O_NONBLOCK);
}

int adaptor_init(zhandle_t *zh)
{
    struct adaptor_threads *adaptor_threads = calloc(1, sizeof(*adaptor_threads));
    if (!adaptor_threads) {
        return -1;
    }

    /* We use a pipe for interrupting select() */
    if(pipe(adaptor_threads->self_pipe)==-1) {
        printf("Can't make a pipe %d\n",errno);
        return -1;
    }
    set_nonblock(adaptor_threads->self_pipe[1]);
    set_nonblock(adaptor_threads->self_pipe[0]);

    zh->adaptor_priv = adaptor_threads;
    pthread_mutex_init(&zh->to_process.lock,0);
    pthread_mutex_init(&zh->to_send.lock,0);
    pthread_mutex_init(&zh->sent_requests.lock,0);
    pthread_cond_init(&zh->sent_requests.cond,0);
    pthread_mutex_init(&zh->completions_to_process.lock,0);
    pthread_cond_init(&zh->completions_to_process.cond,0);
    api_prolog(zh);
    pthread_create(&adaptor_threads->io, 0, do_io, zh);
    api_prolog(zh);
    pthread_create(&adaptor_threads->completion, 0, do_completion, zh);
    return 0;
}

void adaptor_finish(zhandle_t *zh)
{
    struct adaptor_threads *adaptor_threads = zh->adaptor_priv;

    if (zh->state >= 0) {
        fprintf(stderr, "Forcibly setting state to CLOSED\n");
        zh->state = -1;
    }
    adaptor_send_queue(zh,0); // wake up selector
    pthread_mutex_lock(&zh->completions_to_process.lock);
    pthread_cond_broadcast(&zh->completions_to_process.cond);
    pthread_mutex_unlock(&zh->completions_to_process.lock);
    pthread_join(adaptor_threads->io, 0);
    pthread_join(adaptor_threads->completion, 0);
    pthread_mutex_destroy(&zh->to_process.lock);
    pthread_mutex_destroy(&zh->to_send.lock);
    pthread_mutex_destroy(&zh->sent_requests.lock);
    pthread_cond_destroy(&zh->sent_requests.cond);
    pthread_mutex_destroy(&zh->completions_to_process.lock);
    pthread_cond_destroy(&zh->completions_to_process.cond);
    close(adaptor_threads->self_pipe[0]);
    close(adaptor_threads->self_pipe[1]);
    free(adaptor_threads);
    zh->adaptor_priv=0;
}

int adaptor_send_queue(zhandle_t *zh, int timeout)
{
    struct adaptor_threads *adaptor_threads = zh->adaptor_priv;
    char c=0;
    write(adaptor_threads->self_pipe[1], &c, 1);
    return 0;
}

/* These two are declared here because we will run the event loop
 * and not the client */
int zookeeper_interest(zhandle_t *zh, int *fd, int *interest,
        struct timeval *tv);
int zookeeper_process(zhandle_t *zh, int events);

void *do_io(void *v)
{
    zhandle_t *zh = (zhandle_t*)v;
    int fd;
    int interest;
    struct timeval tv;
    fd_set rfds;
    fd_set wfds;
    fd_set efds;
    struct adaptor_threads *adaptor_threads = zh->adaptor_priv;

    FD_ZERO(&rfds);
    FD_ZERO(&wfds);
    FD_ZERO(&efds);
    while(zh->state >= 0) {
        int result;
        zookeeper_interest(zh, &fd, &interest, &tv);
        if (fd != -1) {
            if (interest&ZOOKEEPER_READ) {
                    FD_SET(fd, &rfds);
            } else {
                    FD_CLR(fd, &rfds);
            }
            if (interest&ZOOKEEPER_WRITE) {
                    FD_SET(fd, &wfds);
            } else {
                    FD_CLR(fd, &wfds);
            }
        }
        FD_SET(adaptor_threads->self_pipe[0],&rfds);
        int maxfd=adaptor_threads->self_pipe[0]>fd ? adaptor_threads->self_pipe[0] : fd;
        result = select(maxfd+1, &rfds, &wfds, &efds, &tv);
        interest = 0;
        if (fd != -1) {
            if (FD_ISSET(fd, &rfds)) {
                interest |= ZOOKEEPER_READ;
            }
            if (FD_ISSET(fd, &wfds)) {
                interest |= ZOOKEEPER_WRITE;
            }
        }
        if(FD_ISSET(adaptor_threads->self_pipe[0],&rfds)){
            // flush the pipe
            char b[128];
            while(read(adaptor_threads->self_pipe[0],b,sizeof(b))==sizeof(b)){}
        }
        result = zookeeper_process(zh, interest);
    }
    api_epilog(zh, 0);
    return 0;
}

void *do_completion(void *v)
{
    zhandle_t *zh = v;
    while(zh->state >= 0) {
        pthread_mutex_lock(&zh->completions_to_process.lock);
        while(!zh->completions_to_process.head && zh->state >= 0) {
            pthread_cond_wait(&zh->completions_to_process.cond, &zh->completions_to_process.lock);
        }
        pthread_mutex_unlock(&zh->completions_to_process.lock);
        process_completions(zh);
    }
    api_epilog(zh, 0);
    return 0;
}

int inc_nesting_level(nesting_level_t* nl,int i)
{
    int v;
    pthread_mutex_lock(&nl->lock);
    nl->level+=(i<0?-1:(i>0?1:0));
    v=nl->level;
    pthread_mutex_unlock(&nl->lock);
    return v;
}
