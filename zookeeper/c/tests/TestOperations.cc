#include <cppunit/extensions/HelperMacros.h>

#include <zookeeper.h>
#include "src/zk_adaptor.h"

#include "LibCMocks.h"
#include "ZKMocks.h"
#include "CppAssertHelper.h"
#ifdef THREADED
#include "PthreadMocks.h"
#endif

using namespace std;

class Zookeeper_operations : public CPPUNIT_NS::TestFixture
{
    CPPUNIT_TEST_SUITE(Zookeeper_operations);
    CPPUNIT_TEST(testConcurrentOperations1);
    //CPPUNIT_TEST(testOperationsAndCloseConcurrently1);
    CPPUNIT_TEST_SUITE_END();
    zhandle_t *zh;

    static void watcher(zhandle_t *, int, int, const char *){}
public: 
    void setUp()
    {
        zoo_set_debug_level((ZooLogLevel)0); // disable logging
        zoo_deterministic_conn_order(0);
        zh=0;
    }
    
    void tearDown()
    {
        zookeeper_close(zh);
    }

#ifndef THREADED
    void testConcurrentOperations1()
    {
        
    }
    void testOperationsAndCloseConcurrently1()
    {
        
    }
#else
    class Latch{
    public:
        virtual ~Latch(){}
        virtual void await() =0;
        virtual void signalAndWait() =0;
        virtual void signal() =0;
    };
    
    class CountDownLatch: public Latch{
    public:
        CountDownLatch(int count):count_(count){
            pthread_cond_init(&cond_,0);
            pthread_mutex_init(&mut_,0);            
        }
        virtual ~CountDownLatch(){
            pthread_mutex_lock(&mut_);
            if(count_!=0){
                count_=0;
                pthread_cond_broadcast(&cond_);                
            }
            pthread_mutex_unlock(&mut_);            
            
            pthread_cond_destroy(&cond_);
            pthread_mutex_destroy(&mut_);            
        }

        virtual void await(){
            pthread_mutex_lock(&mut_);
            awaitImpl();
            pthread_mutex_unlock(&mut_);                
        }
        virtual void signalAndWait(){
            pthread_mutex_lock(&mut_);
            signalImpl();
            awaitImpl();
            pthread_mutex_unlock(&mut_);                            
        }
        virtual void signal(){
            pthread_mutex_lock(&mut_);
            signalImpl();
            pthread_mutex_unlock(&mut_);            
        }
    private:
        void awaitImpl(){
            while(count_!=0) 
                pthread_cond_wait(&cond_,&mut_);            
        }
        void signalImpl(){
            if(count_>0){
                count_--;
                pthread_cond_broadcast(&cond_);                
            }
        }
        int count_;
        pthread_mutex_t mut_;
        pthread_cond_t cond_;
    };
    class TestJob{
    public:
        typedef long JobId;
        TestJob():startLatch_(0),endLatch_(0){}
        virtual ~TestJob(){}
        
        virtual void run() =0;
        virtual void validate(const char* file, int line) const =0;
        
        virtual void start(Latch* startLatch=0,Latch* endLatch=0) {
            startLatch_=startLatch;endLatch_=endLatch;
            pthread_create(&thread_, 0, thread, this);
        }
        virtual JobId getJobId() const {
            return (JobId)thread_;
        }
    private:
        void awaitStart(){
            if(startLatch_==0) return;
            startLatch_->signalAndWait();
        }
        void signalFinished(){
            if(endLatch_==0) return;
            endLatch_->signal();
        }
        static void* thread(void* p){
            TestJob* j=(TestJob*)p;
            j->awaitStart();  // wait for the start command
            j->run();
            j->signalFinished();
            pthread_detach(j->thread_);
            return 0;
        }
        Latch* startLatch_;
        Latch* endLatch_;
        pthread_t thread_;
    };
#define VALIDATE_JOB(j) j.validate(__FILE__,__LINE__)
    
    class TestGetJob: public TestJob{
    public:
        static const int REPS=3000;
        TestGetJob(ZookeeperServer* svr,zhandle_t* zh)
            :svr_(svr),zh_(zh),rc_(ZAPIERROR){}
        virtual void run(){
            int i;
            for(i=0;i<REPS;i++){
                char buf;
                int size=sizeof(buf);
                svr_->addSendResponse(new ZooGetResponse("1",1));
                rc_=zoo_get(zh_,"/x/y/z",0,&buf,&size,0);
                if(rc_!=ZOK){
                    break;
                }
            }
            //TEST_TRACE(("Finished %d iterations",i));
        }
        virtual void validate(const char* file, int line) const{
            CPPUNIT_ASSERT_EQUAL_MESSAGE_LOC("ZOK != rc",ZOK,rc_,file,line);
        }
        ZookeeperServer* svr_;
        zhandle_t* zh_;
        int rc_;
    };
    void testConcurrentOperations1()
    {
        // frozen time -- no timeouts and no pings
        Mock_gettimeofday timeMock;
        
        ZookeeperServer zkServer;
        Mock_select selMock(&zkServer,ZookeeperServer::FD);
        // must call zookeeper_close() while all the mocks are in the scope!
        CloseFinally guard(&zh);
        
        zh=zookeeper_init("localhost:2121",watcher,10000,&testClientId,0,0);
        CPPUNIT_ASSERT(zh!=0);
        // make sure the client has connected
        while(zh->state!=CONNECTED_STATE)
            millisleep(2);
                
        TestGetJob j1(&zkServer,zh);
        TestGetJob j2(&zkServer,zh);
        TestGetJob j3(&zkServer,zh);
        TestGetJob j4(&zkServer,zh);
        TestGetJob j5(&zkServer,zh);
        TestGetJob j6(&zkServer,zh);
        TestGetJob j7(&zkServer,zh);
        TestGetJob j8(&zkServer,zh);
        TestGetJob j9(&zkServer,zh);
        TestGetJob j10(&zkServer,zh);

        const int THREAD_COUNT=10;
        CountDownLatch startLatch(THREAD_COUNT);
        CountDownLatch endLatch(THREAD_COUNT);

        j1.start(&startLatch,&endLatch);
        j2.start(&startLatch,&endLatch);
        j3.start(&startLatch,&endLatch);
        j4.start(&startLatch,&endLatch);
        j5.start(&startLatch,&endLatch);
        j6.start(&startLatch,&endLatch);
        j7.start(&startLatch,&endLatch);
        j8.start(&startLatch,&endLatch);
        j9.start(&startLatch,&endLatch);
        j10.start(&startLatch,&endLatch);
        endLatch.await();
        // validate test results
        VALIDATE_JOB(j1);
        VALIDATE_JOB(j2);
        VALIDATE_JOB(j3);
        VALIDATE_JOB(j4);
        VALIDATE_JOB(j5);
        VALIDATE_JOB(j6);
        VALIDATE_JOB(j7);
        VALIDATE_JOB(j8);
        VALIDATE_JOB(j9);
        VALIDATE_JOB(j10);
    }
    class TestGetWithCloseJob: public TestJob{
    public:
        static const int REPS=3000;
        TestGetWithCloseJob(ZookeeperServer* svr,zhandle_t* zh)
            :svr_(svr),zh_(zh),rc_(ZAPIERROR){}
        virtual void run(){
            int i;
            for(i=0;i<REPS;i++){
                char buf;
                int size=sizeof(buf);                
                svr_->addSendResponse(new ZooGetResponse("1",1));
                rc_=zoo_get(zh_,"/x/y/z",0,&buf,&size,0);
                if(rc_!=ZOK){
                    break;
                }
            }
            //TEST_TRACE(("Finished %d iterations",i));
        }
        virtual void validate(const char* file, int line) const{
            CPPUNIT_ASSERT_EQUAL_MESSAGE_LOC("ZOK != rc",ZOK,rc_,file,line);
        }
        ZookeeperServer* svr_;
        zhandle_t* zh_;
        int rc_;
    };

    void testOperationsAndCloseConcurrently1()
    {
        for(int counter=0; counter<1; counter++){
            // frozen time -- no timeouts and no pings
            Mock_gettimeofday timeMock;
            
            ZookeeperServer zkServer;
            Mock_select selMock(&zkServer,ZookeeperServer::FD);
            // must call zookeeper_close() while all the mocks are in the scope!
            CloseFinally guard(&zh);
            
            zh=zookeeper_init("localhost:2121",watcher,10000,&testClientId,0,0);
            CPPUNIT_ASSERT(zh!=0);
            // make sure the client has connected
            while(zh->state!=CONNECTED_STATE)
                millisleep(2);
            
            TestGetWithCloseJob j1(&zkServer,zh);

        }
        
    }
#endif
};

CPPUNIT_TEST_SUITE_REGISTRATION(Zookeeper_operations);
