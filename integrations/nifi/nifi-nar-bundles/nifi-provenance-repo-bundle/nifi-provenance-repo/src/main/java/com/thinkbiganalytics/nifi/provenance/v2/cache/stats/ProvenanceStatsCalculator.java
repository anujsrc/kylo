package com.thinkbiganalytics.nifi.provenance.v2.cache.stats;

import com.thinkbiganalytics.nifi.provenance.model.ActiveFlowFile;
import com.thinkbiganalytics.nifi.provenance.model.ProvenanceEventRecordDTO;
import com.thinkbiganalytics.nifi.provenance.model.stats.AggregatedFeedProcessorStatisticsHolder;
import com.thinkbiganalytics.nifi.provenance.model.stats.ProvenanceEventStats;
import com.thinkbiganalytics.nifi.provenance.model.stats.StatsModel;
import com.thinkbiganalytics.nifi.provenance.v2.writer.ProvenanceEventActiveMqWriter;
import com.thinkbiganalytics.util.SpringApplicationContext;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Created by sr186054 on 8/15/16.
 */
@Component
public class ProvenanceStatsCalculator {

    private static final Logger log = LoggerFactory.getLogger(ProvenanceStatsCalculator.class);

    public static ProvenanceStatsCalculator instance() {
        return (ProvenanceStatsCalculator) SpringApplicationContext.getInstance().getBean("provenanceStatsCalculator");
    }

    @Value("${thinkbig.provenance.aggregation.interval.seconds}")
    private Integer aggregationIntervalSeconds = 10;


    /**
     * Reference to when to send the aggregated events found in the  statsByTime map.
     */
    private final AtomicReference<DateTime> nextSendTime;

    /**
     * Map of items to send
     */
    private Map<Long, AggregatedFeedProcessorStatisticsHolder> statsByTime = new ConcurrentHashMap<>();
    /**
     * flag to lock if we are updating the nextSendTime
     */
    private AtomicBoolean isUpdatingNextSendTime = new AtomicBoolean(false);

    @Autowired
    private ProvenanceEventActiveMqWriter provenanceEventActiveMqWriter;

    public ProvenanceStatsCalculator() {

        nextSendTime = new AtomicReference<>(DateTime.now().plusSeconds(aggregationIntervalSeconds));
        initCheckAndSendTimer();
    }


    /**
     * Check to see if the current time is after the window to send the stats. if so increment the nextSendTime and send off the stats for the previous window
     */
    private void checkAndSend() {
        if (DateTime.now().isAfter(nextSendTime.get())) {
            DateTime previousSendTime = null;
            if (isUpdatingNextSendTime.compareAndSet(false, true)) {
                try {
                    log.info("Update the nextSendTime ");
                    previousSendTime = nextSendTime.get();
                    nextSendTime.set(nextSendTime.get().plusSeconds(aggregationIntervalSeconds));
                    sendStats(previousSendTime.getMillis());
                } finally {
                    isUpdatingNextSendTime.set(false);
                }

            }
        }
    }

    /**
     * Stats are grouped by the nextSendTime and then by Feed and Processor
     */
    private void addStats(ProvenanceEventStats stats) {
        //if now is after the next time to send update the nextTime to send
        checkAndSend();
        if (stats != null) {
            statsByTime.computeIfAbsent(nextSendTime.get().getMillis(), time -> new AggregatedFeedProcessorStatisticsHolder(aggregationIntervalSeconds)).addStat(stats);
        }
    }

    /**
     * Send the stats in the map matching the Key to JMS then remove the elements from the statsByTime map
     */
    public void sendStats(Long key) {
        AggregatedFeedProcessorStatisticsHolder statisticsHolder = statsByTime.get(key);
        log.info("Attempt to send stats for key {} , {} ", key, statisticsHolder);
        if (statisticsHolder != null) {
            if (provenanceEventActiveMqWriter != null) {
                provenanceEventActiveMqWriter.writeStats(statisticsHolder);
                //invalidate the ones that were sent before - some offset
                statsByTime.remove(key);
            }
        }
    }

    public void addFailureStats(ProvenanceEventStats failureStats) {
        addStats(failureStats);
    }


    /**
     * Converts the incoming ProvenanceEvent into an object that can be used to gather statistics (ProvenanceEventStats)
     */
    public ProvenanceEventStats calculateStats(ProvenanceEventRecordDTO event) {
        String feedName = event.getFeedName() == null ? event.getFlowFile().getFeedName() : event.getFeedName();
        if (feedName != null) {
            try {
                ProvenanceEventStats eventStats = StatsModel.toProvenanceEventStats(feedName, event);
                addStats(eventStats);
                if (event.isEndingFlowFileEvent()) {
                    List<ProvenanceEventRecordDTO> completed = completeStatsForParentFlowFiles(event);
                    if (completed != null) {
                        for (ProvenanceEventRecordDTO e : completed) {
                            log.info("**************** SEND COMPLETED JOB EVENT for event: {}, endOfJob: {}, ff:{}, job ff: {}, etype: {}, processor: {} ", e.getEventId(), e.isEndOfJob(),
                                     e.getFlowFileUuid(), e.getJobFlowFileId(), e.getEventType(), e.getComponentName());
                        }
                    }
                }

                return eventStats;
            } catch (Exception e) {
                log.error("Unable to add Statistics for Event {}.  Exception: {} ", event, e.getMessage(), e);
            }
        } else {
            log.error("Unable to add Statistics for Event {}.  Unable to find feed for event ", event);
        }
        return null;
    }


    /**
     * get list of EventStats marking the flowfile as complete for the direct parent flowfiles if the child is complete and the parent is complete
     */
    public List<ProvenanceEventRecordDTO> completeStatsForParentFlowFiles(ProvenanceEventRecordDTO event) {

        ActiveFlowFile rootFlowFile = event.getFlowFile().getRootFlowFile();
        log.info("try to complete parents for {}, root: {},  parents: {} ", event.getFlowFile().getId(), rootFlowFile.getId(), event.getFlowFile().getParents().size());

        if (event.isEndingFlowFileEvent() && event.getFlowFile().hasParents()) {
            log.info("Attempt to complete all {} parent Job flow files that are complete", event.getFlowFile().getParents().size());
            List<ProvenanceEventStats> list = new ArrayList<>();
            List<ProvenanceEventRecordDTO> eventList = new ArrayList<>();
            event.getFlowFile().getParents().stream().filter(parent -> parent.isCurrentFlowFileComplete()).forEach(parent -> {
                ProvenanceEventRecordDTO lastFlowFileEvent = parent.getLastEvent();
                log.info("Completing ff {}, {} ", event.getFlowFile().getRootFlowFile().getId(), event.getFlowFile().getRootFlowFile().isFlowFileCompletionStatsCollected());
                ProvenanceEventStats stats = StatsModel.newJobCompletionProvenanceEventStats(event.getFeedName(), lastFlowFileEvent);
                if (stats != null) {
                    list.add(stats);
                    eventList.add(lastFlowFileEvent);

                }
            });
            if (!list.isEmpty()) {
                aggregateStats(list);
            }
            return eventList;
        }
        return null;

    }


    /**
     * Stores just the stats
     */
    private void aggregateStats(List<ProvenanceEventStats> statsList) {
        if (statsList != null) {
            statsList.stream().forEach(stats -> {
                addStats(stats);
            });
        }
    }



    /**
     * Start a timer to run and get any leftover events and send to JMS This is where the events are not calculated because there is a long delay in provenacne events and they are still waiting in the
     * caches
     */
    private void initCheckAndSendTimer() {
        int interval = aggregationIntervalSeconds;
        ScheduledExecutorService service = Executors
            .newSingleThreadScheduledExecutor();
        service.scheduleAtFixedRate(() -> {
            checkAndSend();
        }, aggregationIntervalSeconds, aggregationIntervalSeconds, TimeUnit.SECONDS);
    }

}