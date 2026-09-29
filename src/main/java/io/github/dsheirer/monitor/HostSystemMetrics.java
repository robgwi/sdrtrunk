/*
 * ****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.monitor;

import java.lang.management.ManagementFactory;

/** Provides a consistent snapshot of host and JVM resource usage. */
public final class HostSystemMetrics
{
    private HostSystemMetrics()
    {
    }

    public static Snapshot snapshot()
    {
        Runtime runtime = Runtime.getRuntime();
        double cpuLoad = -1;
        long totalMemory = -1;
        long freeMemory = -1;
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();

        if(bean instanceof com.sun.management.OperatingSystemMXBean operatingSystem)
        {
            cpuLoad = operatingSystem.getCpuLoad();
            totalMemory = operatingSystem.getTotalMemorySize();
            freeMemory = operatingSystem.getFreeMemorySize();
        }

        return new Snapshot(cpuLoad, totalMemory, freeMemory,
            totalMemory >= 0 && freeMemory >= 0 ? Math.max(0, totalMemory - freeMemory) : -1,
            runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(), runtime.availableProcessors(),
            System.getProperty("os.name", "Unknown"), ManagementFactory.getRuntimeMXBean().getUptime());
    }

    public record Snapshot(double cpuLoad, long totalMemory, long freeMemory, long usedMemory,
                           long jvmUsedMemory, long jvmMaximumMemory, int processors, String operatingSystem,
                           long jvmUptimeMs)
    {
        public boolean hasCpuLoad()
        {
            return Double.isFinite(cpuLoad) && cpuLoad >= 0;
        }

        public boolean hasHostMemory()
        {
            return totalMemory > 0 && usedMemory >= 0;
        }
    }
}
