package com.octo.reactive.audit;

import com.octo.reactive.audit.annotation.AuditReactiveException;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;

import static com.octo.reactive.audit.Latency.HIGH;

/**
 * Created by pprados on 09/05/2014.
 */
@Aspect
public class WithLatencyAspect extends AbstractAudit
{
	@Before("execution(@com.octo.reactive.audit.annotation.WithLatency * *(..) )")
	public void beforeSuppress(JoinPoint thisJoinPoint)
			throws AuditReactiveException
	{
		latency(HIGH, thisJoinPoint);
	}
}