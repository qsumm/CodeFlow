package com.ainative.scheduledtask.compat;

import com.ainative.scheduledtask.ScheduledTaskController;
import com.ainative.scheduledtask.ScheduledTaskDocumentController;
import com.ainative.scheduledtask.ScheduledTaskRunCommentService;
import com.ainative.scheduledtask.ScheduledTaskRunController;
import com.ainative.scheduledtask.ScheduledTaskTriggerService;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import com.ainative.common.error.BizException;
import com.ainative.common.error.ErrorCode;
import com.ainative.scheduledtask.ScheduledTaskRunDao;
import com.ainative.scheduledtask.ScheduledTaskService;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ScheduledTaskCapabilityBoundaryTest {

    @Test
    void unavailableHttpCapabilityFailsBeforeControllerScheduledDependencies() {
        ScheduledTaskService taskService = mock(ScheduledTaskService.class);
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        ScheduledTaskTriggerService triggerService = mock(ScheduledTaskTriggerService.class);
        ScheduledTaskCapabilityGuard guard = mock(ScheduledTaskCapabilityGuard.class);
        doThrow(new BizException(ErrorCode.SCHEDULED_TASK_SCHEMA_NOT_READY))
                .when(guard).requireAvailable("http");
        ScheduledTaskController target = new ScheduledTaskController(taskService, runDao, triggerService);
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new ScheduledTaskCapabilityAspect(guard));
        ScheduledTaskController controller = factory.getProxy();

        BizException failure = assertThrows(BizException.class,
                () -> controller.list(null, null, null, null, 20, 0));

        assertEquals("30006", failure.getCode());
        verifyNoInteractions(taskService, runDao, triggerService);
    }

    @Test
    void everyScheduledHttpControllerIsCapabilityProtected() {
        assertEntry(ScheduledTaskController.class, "http");
        assertEntry(ScheduledTaskDocumentController.class, "http");
        assertEntry(ScheduledTaskRunController.class, "http");
    }

    @Test
    void capabilityEndpointRemainsSchemaIndependent() {
        assertFalse(AnnotatedElementUtils.hasAnnotation(
                ScheduledTaskCapabilityController.class, RequiresScheduledTaskCapability.class));
    }

    @Test
    void triggerAndRunCommentPublicEntriesAreCapabilityProtected() {
        for (Method method : ScheduledTaskTriggerService.class.getDeclaredMethods()) {
            if (method.getName().startsWith("fire") && java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                assertEntry(method, "scheduler");
            }
        }
        for (Method method : ScheduledTaskRunCommentService.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())
                    && (method.getName().startsWith("add") || method.getName().equals("list"))) {
                assertEntry(method, "http");
            }
        }
    }

    private static void assertEntry(java.lang.reflect.AnnotatedElement element, String expected) {
        RequiresScheduledTaskCapability annotation = AnnotatedElementUtils.findMergedAnnotation(
                element, RequiresScheduledTaskCapability.class);
        assertNotNull(annotation, () -> "missing capability annotation on " + element);
        assertEquals(expected, annotation.entry());
    }
}
