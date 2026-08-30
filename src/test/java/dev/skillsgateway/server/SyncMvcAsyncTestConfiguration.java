package dev.skillsgateway.server;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Runs MVC async request handling on the request thread.
 *
 * <p>{@code /api/audit/export} returns a {@code StreamingResponseBody}, so by default Spring writes
 * the body from a task-executor thread while the calling thread is still unwinding the security
 * filter chain, writing headers onto the same unsynchronized {@code MockHttpServletResponse}. That
 * race throws {@code ConcurrentModificationException} intermittently. Writing the body inline
 * removes the second thread; the callable still dispatches rather than completes, so
 * {@code request().asyncStarted()} continues to hold.
 */
@TestConfiguration(proxyBeanMethods = false)
class SyncMvcAsyncTestConfiguration implements WebMvcConfigurer {

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(new AsyncTaskExecutor() {
            @Override
            public void execute(Runnable task) {
                task.run();
            }
        });
    }
}
