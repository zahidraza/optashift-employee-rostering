package org.optaplanner.openshift.employeerostering.gwtui.client.viewport.availabilityroster;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.gwt.core.client.Scheduler;
import com.google.gwt.core.client.Scheduler.RepeatingCommand;
import elemental2.promise.Promise;
import org.jboss.errai.ioc.client.api.ManagedInstance;
import org.optaplanner.openshift.employeerostering.gwtui.client.app.spinner.LoadingSpinner;
import org.optaplanner.openshift.employeerostering.gwtui.client.common.EventManager;
import org.optaplanner.openshift.employeerostering.gwtui.client.common.FailureShownRestCallback;
import org.optaplanner.openshift.employeerostering.gwtui.client.common.LocalDateRange;
import org.optaplanner.openshift.employeerostering.gwtui.client.common.Lockable;
import org.optaplanner.openshift.employeerostering.gwtui.client.tenant.TenantStore;
import org.optaplanner.openshift.employeerostering.gwtui.client.util.CommonUtils;
import org.optaplanner.openshift.employeerostering.gwtui.client.util.PromiseUtils;
import org.optaplanner.openshift.employeerostering.gwtui.client.viewport.grid.Lane;
import org.optaplanner.openshift.employeerostering.shared.employee.view.EmployeeAvailabilityView;
import org.optaplanner.openshift.employeerostering.shared.roster.Pagination;
import org.optaplanner.openshift.employeerostering.shared.roster.RosterRestServiceBuilder;
import org.optaplanner.openshift.employeerostering.shared.roster.view.AvailabilityRosterView;
import org.optaplanner.openshift.employeerostering.shared.shift.view.ShiftView;

import static org.optaplanner.openshift.employeerostering.gwtui.client.common.EventManager.Event.AVAILABILITY_ROSTER_DATE_RANGE;
import static org.optaplanner.openshift.employeerostering.gwtui.client.common.EventManager.Event.AVAILABILITY_ROSTER_INVALIDATE;
import static org.optaplanner.openshift.employeerostering.gwtui.client.common.EventManager.Event.AVAILABILITY_ROSTER_PAGINATION;
import static org.optaplanner.openshift.employeerostering.gwtui.client.common.EventManager.Event.AVAILABILITY_ROSTER_UPDATE;
import static org.optaplanner.openshift.employeerostering.gwtui.client.common.EventManager.Event.SOLVE_END;
import static org.optaplanner.openshift.employeerostering.gwtui.client.common.EventManager.Event.SOLVE_START;

@Singleton
public class AvailabilityRosterPageViewportBuilder {

    @Inject
    private PromiseUtils promiseUtils;

    @Inject
    private CommonUtils commonUtils;

    @Inject
    private TenantStore tenantStore;

    @Inject
    private ManagedInstance<ShiftGridObject> shiftGridObjectInstances;

    @Inject
    private ManagedInstance<AvailabilityGridObject> employeeAvailabilityGridObjectInstances;

    @Inject
    private EventManager eventManager;

    @Inject
    private LoadingSpinner loadingSpinner;

    private AvailabilityRosterPageViewport viewport;

    private boolean isUpdatingRoster;
    private boolean isSolving;

    private final int WORK_LIMIT_PER_CYCLE = 50;

    private long currentWorkerStartTime;
    private Pagination pagination;
    private LocalDateRange localDateRange;

    @PostConstruct
    private void init() {
        pagination = Pagination.of(0, 10);
        eventManager.subscribeToEvent(SOLVE_START, (m) -> this.onSolveStart());
        eventManager.subscribeToEvent(SOLVE_END, (m) -> this.onSolveEnd());
        eventManager.subscribeToEvent(AVAILABILITY_ROSTER_PAGINATION, (pagination) -> {
            this.pagination = pagination;
            buildAvailabilityRosterViewport(viewport);
        });
        eventManager.subscribeToEvent(AVAILABILITY_ROSTER_INVALIDATE, (nil) -> {
            buildAvailabilityRosterViewport(viewport);
        });
        eventManager.subscribeToEvent(AVAILABILITY_ROSTER_DATE_RANGE, dr -> {
            localDateRange = dr;
            buildAvailabilityRosterViewport(viewport);
        });
        RosterRestServiceBuilder.getRosterState(tenantStore.getCurrentTenantId(),
                                                FailureShownRestCallback.onSuccess((rs) -> {
                                                    eventManager.fireEvent(AVAILABILITY_ROSTER_DATE_RANGE, new LocalDateRange(rs.getFirstDraftDate(), rs.getFirstDraftDate().plusDays(7)));
                                                }));
    }

    public AvailabilityRosterPageViewportBuilder withViewport(AvailabilityRosterPageViewport viewport) {
        this.viewport = viewport;
        return this;
    }

    public RepeatingCommand getWorkerCommand(final AvailabilityRosterView view, final Lockable<Map<Long, Lane<LocalDateTime, AvailabilityRosterMetadata>>> lockableLaneMap, final long timeWhenInvoked) {
        currentWorkerStartTime = timeWhenInvoked;

        if (view.getEmployeeList().isEmpty() && !pagination.isOnFirstPage()) {
            eventManager.fireEvent(AVAILABILITY_ROSTER_PAGINATION, pagination.previousPage());
            return () -> false;
        }

        final Iterator<ShiftView> shiftViewsToAdd = commonUtils.flatten(view.getEmployeeIdToShiftViewListMap().values()).iterator();
        final Iterator<EmployeeAvailabilityView> employeeAvaliabilitiesViewsToAdd = commonUtils.flatten(view.getEmployeeIdToAvailabilityViewListMap().values()).iterator();

        setUpdatingRoster(true);
        eventManager.fireEvent(AVAILABILITY_ROSTER_UPDATE, view);

        return new RepeatingCommand() {

            final long timeWhenStarted = timeWhenInvoked;
            final Set<Long> laneIdFilteredSet = new HashSet<>();

            @Override
            public boolean execute() {
                if (timeWhenStarted != getCurrentWorkerStartTime()) {
                    return false;
                }
                lockableLaneMap.acquireIfPossible(laneMap -> {
                    int workDone = 0;
                    while (shiftViewsToAdd.hasNext() && workDone < WORK_LIMIT_PER_CYCLE) {
                        ShiftView toAdd = shiftViewsToAdd.next();
                        if (!laneIdFilteredSet.contains(toAdd.getEmployeeId())) {
                            Set<Long> shiftViewsId = view.getEmployeeIdToShiftViewListMap().getOrDefault(toAdd.getEmployeeId(), Collections.emptyList()).stream().map(sv -> sv.getId()).collect(Collectors.toSet());
                            Set<Long> availabilityViewsId = view.getEmployeeIdToAvailabilityViewListMap().getOrDefault(toAdd.getEmployeeId(), Collections.emptyList()).stream().map(sv -> sv.getId()).collect(Collectors
                                                                                                                                                                                                                        .toSet());
                            laneMap.get(toAdd.getEmployeeId()).filterGridObjects(ShiftGridObject.class,
                                                                                 (sv) -> shiftViewsId.contains(sv.getId()));
                            laneMap.get(toAdd.getEmployeeId()).filterGridObjects(AvailabilityGridObject.class,
                                                                                 (sv) -> availabilityViewsId.contains(sv.getId()));
                            laneIdFilteredSet.add(toAdd.getEmployeeId());
                        }
                        laneMap.get(toAdd.getEmployeeId()).addOrUpdateGridObject(
                                                                                 ShiftGridObject.class, toAdd.getId(), () -> {
                                                                                     ShiftGridObject out = shiftGridObjectInstances.get();
                                                                                     out.withShiftView(toAdd);
                                                                                     return out;
                                                                                 }, (s) -> {
                                                                                     s.withShiftView(toAdd);
                                                                                     return null;
                                                                                 });
                        workDone++;
                    }

                    while (employeeAvaliabilitiesViewsToAdd.hasNext() && workDone < WORK_LIMIT_PER_CYCLE) {
                        EmployeeAvailabilityView toAdd = employeeAvaliabilitiesViewsToAdd.next();
                        if (!laneIdFilteredSet.contains(toAdd.getEmployeeId())) {
                            Set<Long> shiftViewsId = view.getEmployeeIdToShiftViewListMap().getOrDefault(toAdd.getEmployeeId(), Collections.emptyList()).stream().map(sv -> sv.getId()).collect(Collectors.toSet());
                            Set<Long> availabilityViewsId = view.getEmployeeIdToAvailabilityViewListMap().getOrDefault(toAdd.getEmployeeId(), Collections.emptyList()).stream().map(sv -> sv.getId()).collect(Collectors
                                                                                                                                                                                                                        .toSet());
                            laneMap.get(toAdd.getEmployeeId()).filterGridObjects(ShiftGridObject.class,
                                                                                 (sv) -> shiftViewsId.contains(sv.getId()));
                            laneMap.get(toAdd.getEmployeeId()).filterGridObjects(AvailabilityGridObject.class,
                                                                                 (sv) -> availabilityViewsId.contains(sv.getId()));
                            laneIdFilteredSet.add(toAdd.getEmployeeId());
                        }
                        laneMap.get(toAdd.getEmployeeId()).addOrUpdateGridObject(
                                                                                 AvailabilityGridObject.class, toAdd.getId(), () -> {
                                                                                     AvailabilityGridObject out = employeeAvailabilityGridObjectInstances.get();
                                                                                     out.withEmployeeAvailabilityView(toAdd);
                                                                                     return out;
                                                                                 }, (a) -> {
                                                                                     a.withEmployeeAvailabilityView(toAdd);
                                                                                     return null;
                                                                                 });
                        workDone++;
                    }

                    if (!shiftViewsToAdd.hasNext() && !employeeAvaliabilitiesViewsToAdd.hasNext()) {
                        laneMap.forEach((l, lane) -> lane.endModifying());
                        loadingSpinner.hideFor(viewport.getLoadingTaskId());
                        setUpdatingRoster(false);
                    }
                });
                return shiftViewsToAdd.hasNext() || employeeAvaliabilitiesViewsToAdd.hasNext();
            }
        };
    }

    private void setUpdatingRoster(boolean isUpdatingRoster) {
        this.isUpdatingRoster = isUpdatingRoster;
    }

    public boolean isSolving() {
        return isSolving;
    }

    private long getCurrentWorkerStartTime() {
        return currentWorkerStartTime;
    }

    public void onSolveStart() {
        viewport.lock();
        isSolving = true;
        Scheduler.get().scheduleFixedPeriod(() -> {
            if (!isUpdatingRoster) {
                setUpdatingRoster(true);
                getAvailabilityRosterView().then(srv -> {
                    viewport.refresh(srv);
                    return promiseUtils.resolve();
                });
            }
            return isSolving();
        }, 2000);
    }

    public void onSolveEnd() {
        viewport.unlock();
        isSolving = false;
    }

    public Promise<Void> buildAvailabilityRosterViewport(final AvailabilityRosterPageViewport toBuild) {
        return getAvailabilityRosterView().then((erv) -> {
            toBuild.refresh(erv);
            return promiseUtils.resolve();
        });
    }

    public Promise<AvailabilityRosterView> getAvailabilityRosterView() {
        return promiseUtils
                           .promise(
                                    (res, rej) -> RosterRestServiceBuilder.getAvailabilityRosterView(tenantStore.getCurrentTenantId(), pagination.getPageNumber(), pagination.getNumberOfItemsPerPage(),
                                                                                                     localDateRange
                                                                                                                   .getStartDate()
                                                                                                                   .toString(),
                                                                                                     localDateRange.getEndDate().toString(),
                                                                                                     FailureShownRestCallback.onSuccess((s) -> {
                                                                                                         res.onInvoke(s);
                                                                                                     })));
    }
}
