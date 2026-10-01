package com.instanthelp.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.instanthelp.AbstractMatchingTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Many requesters match at the same instant: Postgres must hand each helper to at most one of them. */
class MatchingConcurrencyTest extends AbstractMatchingTest {

    private static final String CATEGORY = "ASSEMBLY";

    private record Outcome(UUID taskId, int status, String body) {}

    @Test
    void twoRequestersRacingForOneHelperExactlyOneWins() throws Exception {
        Helper helper = helperAvailableAt(30.0, 30.0, CATEGORY, 5000);
        List<String> requesters = List.of(signInNewUser(), signInNewUser());
        List<UUID> tasks = new ArrayList<>();
        for (String r : requesters) {
            tasks.add(createTask(r, 30.0, 30.0, CATEGORY));
        }

        List<Outcome> outcomes = matchAllAtOnce(requesters, tasks);

        assertThat(outcomes).filteredOn(o -> o.status() == 200).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o.status() == 404).hasSize(1)
                .allSatisfy(o -> assertThat(o.body()).contains("NO_HELPER_AVAILABLE"));

        UUID winningTask = outcomes.stream().filter(o -> o.status() == 200).findFirst().orElseThrow().taskId();
        UUID losingTask = outcomes.stream().filter(o -> o.status() == 404).findFirst().orElseThrow().taskId();
        assertThat(availabilityRow(helper.availabilityId())).containsEntry("status", "OFFERED")
                .containsEntry("version", 2L); // reserved once: exactly one version bump
        assertThat(availabilityRow(helper.availabilityId()).get("current_task_id")).isEqualTo(winningTask);
        assertThat(taskStatus(winningTask)).isEqualTo("MATCHING");
        assertThat(taskStatus(losingTask)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where partition_key = ? and event_type = 'HelperOffered'",
                Long.class, helper.userId().toString())).isEqualTo(1L);
    }

    @Test
    void sixRequestersRacingForThreeHelpersEachHelperIsOfferedOnce() throws Exception {
        List<Helper> helpers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            helpers.add(helperAvailableAt(31.0 + i * 0.0009, 31.0, CATEGORY, 5000)); // all within ~200 m
        }
        List<String> requesters = new ArrayList<>();
        List<UUID> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String r = signInNewUser();
            requesters.add(r);
            tasks.add(createTask(r, 31.0, 31.0, CATEGORY));
        }

        List<Outcome> outcomes = matchAllAtOnce(requesters, tasks);

        assertThat(outcomes).filteredOn(o -> o.status() == 200).hasSize(3);
        assertThat(outcomes).filteredOn(o -> o.status() == 404).hasSize(3)
                .allSatisfy(o -> assertThat(o.body()).contains("NO_HELPER_AVAILABLE"));

        List<Object> offeredTasks = new ArrayList<>();
        for (Helper h : helpers) {
            var row = availabilityRow(h.availabilityId());
            assertThat(row).containsEntry("status", "OFFERED").containsEntry("version", 2L);
            offeredTasks.add(row.get("current_task_id"));
        }
        assertThat(offeredTasks).doesNotHaveDuplicates() // no task got two helpers, no helper two tasks
                .containsExactlyInAnyOrderElementsOf(outcomes.stream().filter(o -> o.status() == 200).map(Outcome::taskId).toList());
        for (Outcome o : outcomes) {
            assertThat(taskStatus(o.taskId())).isEqualTo(o.status() == 200 ? "MATCHING" : "OPEN");
        }
    }

    private List<Outcome> matchAllAtOnce(List<String> requesters, List<UUID> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requesters.size());
        try {
            CountDownLatch ready = new CountDownLatch(requesters.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < requesters.size(); i++) {
                String token = requesters.get(i);
                UUID taskId = tasks.get(i);
                Callable<Outcome> call = () -> {
                    ready.countDown();
                    go.await();
                    var res = mvc.perform(post("/v1/tasks/{id}/match", taskId).header("Authorization", bearer(token))).andReturn();
                    return new Outcome(taskId, res.getResponse().getStatus(), res.getResponse().getContentAsString());
                };
                futures.add(pool.submit(call));
            }
            ready.await();
            go.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> f : futures) {
                outcomes.add(f.get());
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
