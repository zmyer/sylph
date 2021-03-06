/*
 * Copyright (C) 2018 The Sylph Authors
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
package ideal.sylph.main.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import ideal.common.classloader.DirClassLoader;
import ideal.common.classloader.ThreadContextClassLoader;
import ideal.sylph.etl.PipelinePlugin;
import ideal.sylph.main.server.ServerMainConfig;
import ideal.sylph.spi.Runner;
import ideal.sylph.spi.RunnerContext;
import ideal.sylph.spi.job.ContainerFactory;
import ideal.sylph.spi.job.Flow;
import ideal.sylph.spi.job.Job;
import ideal.sylph.spi.job.JobActuator;
import ideal.sylph.spi.job.JobActuatorHandle;
import ideal.sylph.spi.job.JobConfig;
import ideal.sylph.spi.job.JobContainer;
import ideal.sylph.spi.job.JobHandle;
import ideal.sylph.spi.model.PipelinePluginInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.validation.constraints.NotNull;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/**
 * RunnerManager
 */
public class RunnerManager
{
    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());
    private static final Logger logger = LoggerFactory.getLogger(RunnerManager.class);
    private final Map<String, JobActuator> jobActuatorMap = new HashMap<>();
    private final PipelinePluginLoader pluginLoader;
    private final ServerMainConfig config;

    @Inject
    public RunnerManager(PipelinePluginLoader pluginLoader, ServerMainConfig config)
    {
        this.pluginLoader = requireNonNull(pluginLoader, "pluginLoader is null");
        this.config = requireNonNull(config, "config is null");
    }

    public void createRunner(final Runner runner)
    {
        RunnerContext runnerContext = pluginLoader::getPluginsInfo;

        logger.info("Runner: {} starts loading {}", runner.getClass().getName(), PipelinePlugin.class.getName());

        checkArgument(runner.getContainerFactory() != null, runner.getClass() + " getContainerFactory() return null");
        final ContainerFactory factory;
        try {
            factory = runner.getContainerFactory().newInstance();
        }
        catch (InstantiationException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }

        runner.create(runnerContext).forEach(jobActuatorHandle -> {
            JobActuator jobActuator = new JobActuatorImpl(jobActuatorHandle, factory);
            String name = jobActuator.getInfo().getName();
            checkState(!jobActuatorMap.containsKey(name), String.format("Multiple entries with same key: %s=%s and %s=%s", name, jobActuatorMap.get(name), name, jobActuator));

            jobActuatorMap.put(name, jobActuator);
        });
    }

    /**
     * 创建job 运行时
     */
    JobContainer createJobContainer(@Nonnull Job job, String jobInfo)
    {
        String jobType = requireNonNull(job.getActuatorName(), "job Actuator Name is null " + job.getId());
        JobActuator jobActuator = jobActuatorMap.get(jobType);
        checkArgument(jobActuator != null, jobType + " not exists");
        try (ThreadContextClassLoader ignored = new ThreadContextClassLoader(jobActuator.getHandleClassLoader())) {
            switch (config.getRunMode().toLowerCase()) {
                case "yarn":
                    return jobActuator.getFactory().getYarnContainer(job, jobInfo);
                case "local":
                    return jobActuator.getFactory().getLocalContainer(job, jobInfo);
                default:
                    throw new IllegalArgumentException("this job.runtime.mode " + config.getRunMode() + " have't support!");
            }
        }
    }

    public Job formJobWithFlow(String jobId, byte[] flowBytes, Map configBytes)
            throws Exception
    {
        String actuatorName = JobConfig.load(configBytes).getType();
        JobActuator jobActuator = jobActuatorMap.get(actuatorName);
        checkArgument(jobActuator != null, "job [" + jobId + "] loading error! JobActuator:[" + actuatorName + "] not find,only " + jobActuatorMap.keySet());

        JobConfig jobConfig = MAPPER.convertValue(configBytes, jobActuator.getHandle().getConfigParser());
        return formJobWithFlow(jobId, flowBytes, jobActuator, jobConfig);
    }

    public Job formJobWithFlow(String jobId, byte[] flowBytes, byte[] configBytes)
            throws Exception
    {
        String actuatorName = JobConfig.load(configBytes).getType();
        JobActuator jobActuator = jobActuatorMap.get(actuatorName);
        checkArgument(jobActuator != null, "job [" + jobId + "] loading error! JobActuator:[" + actuatorName + "] not find,only " + jobActuatorMap.keySet());

        JobConfig jobConfig = MAPPER.readValue(configBytes, jobActuator.getHandle().getConfigParser());
        return formJobWithFlow(jobId, flowBytes, jobActuator, jobConfig);
    }

    public Collection<JobActuator.ActuatorInfo> getAllActuatorsInfo()
    {
        return jobActuatorMap.values()
                .stream()
                .distinct().map(JobActuator::getInfo)
                .collect(Collectors.toList());
    }

    public List<PipelinePluginInfo> getPlugins()
    {
        return jobActuatorMap.values()
                .stream()
                .flatMap(x -> x.getHandle().getPluginManager().getAllPlugins().stream())
                .distinct()
                .collect(Collectors.toList());
    }

    public List<PipelinePluginInfo> getPlugins(String actuator)
    {
        JobActuator jobActuator = requireNonNull(jobActuatorMap.get(actuator), "job actuator [" + actuator + "] not exists");
        return Lists.newArrayList(jobActuator.getHandle().getPluginManager().getAllPlugins());
    }

    private Job formJobWithFlow(String jobId, byte[] flowBytes, JobActuator jobActuator, JobConfig jobConfig)
            throws Exception
    {
        JobActuatorHandle jobActuatorHandle = jobActuator.getHandle();
        String actuatorName = jobConfig.getType();

        File jobWorkDir = new File(config.getJobWorkDir(), jobId);
        try (DirClassLoader jobClassLoader = new DirClassLoader(null, jobActuator.getHandleClassLoader())) {
            jobClassLoader.addDir(jobWorkDir);

            Flow flow = jobActuatorHandle.formFlow(flowBytes);
            jobClassLoader.addJarFiles(jobActuatorHandle.parserFlowDepends(flow));
            JobHandle jobHandle = jobActuatorHandle.formJob(jobId, flow, jobConfig, jobClassLoader);
            Collection<URL> dependFiles = getJobDependFiles(jobClassLoader);
            return new Job()
            {
                @NotNull
                @Override
                public String getId()
                {
                    return jobId;
                }

                @Override
                public File getWorkDir()
                {
                    return jobWorkDir;
                }

                @Override
                public Collection<URL> getDepends()
                {
                    return dependFiles;
                }

                @NotNull
                @Override
                public String getActuatorName()
                {
                    return actuatorName;
                }

                @NotNull
                @Override
                public JobHandle getJobHandle()
                {
                    return jobHandle;
                }

                @NotNull
                @Override
                public JobConfig getConfig()
                {
                    return jobConfig;
                }

                @NotNull
                @Override
                public Flow getFlow()
                {
                    return flow;
                }
            };
        }
    }

    private static Collection<URL> getJobDependFiles(final ClassLoader jobClassLoader)
    {
        ImmutableList.Builder<URL> builder = ImmutableList.<URL>builder();
        if (jobClassLoader instanceof URLClassLoader) {
            builder.add(((URLClassLoader) jobClassLoader).getURLs());

            final ClassLoader parentClassLoader = jobClassLoader.getParent();
            if (parentClassLoader instanceof URLClassLoader) {
                builder.add(((URLClassLoader) parentClassLoader).getURLs());
            }
        }
        return builder.build().stream().collect(Collectors.toMap(URL::getPath, v -> v, (x, y) -> y))  //distinct
                .values().stream().sorted(Comparator.comparing(URL::getPath))
                .collect(Collectors.toList());
    }
}
