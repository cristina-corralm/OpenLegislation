package gov.nysenate.openleg.dao.calendar;

import com.google.common.collect.*;
import gov.nysenate.openleg.dao.base.*;
import gov.nysenate.openleg.model.base.Version;
import gov.nysenate.openleg.model.bill.BillId;
import gov.nysenate.openleg.model.calendar.*;
import gov.nysenate.openleg.model.sobi.SobiFragment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.collect.ImmutableMap.of;
import static gov.nysenate.openleg.dao.calendar.SqlCalendarQuery.*;
import static gov.nysenate.openleg.util.DateUtils.toDate;

@Repository
public class SqlCalendarDao extends SqlBaseDao implements CalendarDao
{
    private static final Logger logger = LoggerFactory.getLogger(SqlCalendarDao.class);

    /** {@inheritDoc} */
    @Override
    public Calendar getCalendar(CalendarId calendarId) throws DataAccessException {
        ImmutableParams calParams = ImmutableParams.from(getCalendarIdParams(calendarId));
        // Get the base calendar
        Calendar calendar = jdbcNamed.queryForObject(SELECT_CALENDAR.getSql(schema()), calParams, new CalendarRowMapper());
        // Get the supplementals
        calendar.setSupplementalMap(getCalSupplementals(calParams));
        // Get the active lists
        calendar.setActiveListMap(getActiveListMap(calParams));
        // Calendar is fully constructed
        return calendar;
    }

    /** {@inheritDoc} */
    @Override
    public CalendarActiveList getActiveList(CalendarActiveListId calendarActiveListId) throws DataAccessException {
        ImmutableParams params = ImmutableParams.from(getCalendarActiveListIdParams(calendarActiveListId));
        CalendarActiveList activeList = jdbcNamed.queryForObject(SELECT_CALENDAR_ACTIVE_LIST.getSql(schema()), params, new CalendarActiveListRowMapper());
        activeList.setEntries(getActiveListEntries(params));
        return activeList;
    }

    /** {@inheritDoc} */
    @Override
    public CalendarSupplemental getFloorCalendar(CalendarSupplementalId calendarSupplementalId) throws DataAccessException {
        ImmutableParams params = ImmutableParams.from(getCalendarSupplementalIdParams(calendarSupplementalId));
        CalendarSupplemental floorCalendar = jdbcNamed.queryForObject(SELECT_CALENDAR_SUP.getSql(schema()), params, new CalendarSupRowMapper());
        floorCalendar.setSectionEntries(getCalSupEntries(params));
        return floorCalendar;
    }

    /** {@inheritDoc} */
    @Override
    public int getCalendarCount(int year) {
        ImmutableParams yearParam = ImmutableParams.from(new MapSqlParameterSource("year", year));
        return jdbcNamed.queryForObject(SELECT_CALENDARS_COUNT.getSql(schema()), yearParam, Integer.class);
    }

    /** {@inheritDoc} */
    @Override
    public int getActiveListCount(int year) {
        ImmutableParams yearParam = ImmutableParams.from(new MapSqlParameterSource("year", year));
        return jdbcNamed.queryForObject(SELECT_CALENDAR_ACTIVE_LISTS_BY_YEAR_COUNT.getSql(schema()), yearParam, Integer.class);
    }

    /** {@inheritDoc} */
    @Override
    public int getFloorCalendarCount(int year) {
        ImmutableParams yearParam = ImmutableParams.from(new MapSqlParameterSource("year", year));
        return jdbcNamed.queryForObject(SELECT_CALENDAR_SUPS_BY_YEAR_COUNT.getSql(schema()), yearParam, Integer.class);
    }

    /** {@inheritDoc} */
    @Override
    public List<Calendar> getCalendars(int year, SortOrder calOrder, LimitOffset limitOffset) {
        OrderBy orderBy = new OrderBy("calendar_no", calOrder);
        ImmutableParams yearParam = ImmutableParams.from(new MapSqlParameterSource("year", year));
        List<Calendar> calendars = jdbcNamed.query(SELECT_CALENDARS.getSql(schema(), orderBy, limitOffset), yearParam, new CalendarRowMapper());
        ListMultimap<CalendarId, CalendarActiveList> activeLists =
                Multimaps.index(getActiveLists(year, calOrder, LimitOffset.ALL), CalendarActiveList::getCalendarId);
        ListMultimap<CalendarId, CalendarSupplemental> calSups =
                Multimaps.index(getFloorCalendars(year, calOrder, LimitOffset.ALL), CalendarSupplemental::getCalendarId);
        for (Calendar calendar : calendars) {
            calendar.setActiveListMap(activeLists.get(calendar.getId()).stream()
                    .collect(Collectors.toMap(CalendarActiveList::getSequenceNo, Function.identity(), (a,b) -> b, TreeMap::new)));
            calendar.setSupplementalMap(calSups.get(calendar.getId()).stream()
                    .collect(Collectors.toMap(CalendarSupplemental::getVersion, Function.identity(), (a, b) -> b, TreeMap::new)));
        }
        return calendars;
    }

    /** {@inheritDoc} */
    @Override
    public List<CalendarActiveList> getActiveLists(int year, SortOrder sortOrder, LimitOffset limitOffset) throws DataAccessException {
        OrderBy orderBy = new OrderBy("calendar_no", sortOrder, "sequence_no", sortOrder);
        ImmutableParams yearParam = ImmutableParams.from(new MapSqlParameterSource("year", year));
        return jdbcNamed.query(SELECT_CALENDAR_ACTIVE_LISTS_BY_YEAR.getSql(schema(), orderBy, limitOffset),
                yearParam, new CalendarActiveListRowMapper());
    }

    /** {@inheritDoc} */
    @Override
    public List<CalendarSupplemental> getFloorCalendars(int year, SortOrder sortOrder, LimitOffset limitOffset) throws DataAccessException {
        OrderBy orderBy = new OrderBy("calendar_no", sortOrder, "sup_version", sortOrder);
        ImmutableParams yearParam = ImmutableParams.from(new MapSqlParameterSource("year", year));
        return jdbcNamed.query(SELECT_CALENDAR_SUPS_BY_YEAR.getSql(schema(), orderBy, limitOffset),
                yearParam, new CalendarSupRowMapper());
    }

    /** {@inheritDoc} */
    @Override
    public void updateCalendar(Calendar calendar, SobiFragment fragment) throws DataAccessException {
        logger.trace("Updating calendar {} in database...", calendar);
        ImmutableParams calParams = ImmutableParams.from(getCalendarParams(calendar, fragment));
        // Update base calendar
        if (jdbcNamed.update(UPDATE_CALENDAR.getSql(schema()), calParams) == 0) {
            jdbcNamed.update(INSERT_CALENDAR.getSql(schema()), calParams);
        }
        // Update the associated calendar supplementals
        updateCalSupplementals(calendar, fragment, calParams);
        // Update the associated active lists
        updateCalActiveLists(calendar, fragment, calParams);
    }

    /** --- Internal Methods --- */

    /**
     * Retrieves all the supplementals for a particular calendar.
     */
    private TreeMap<Version, CalendarSupplemental> getCalSupplementals(ImmutableParams calParams) {
        List<CalendarSupplemental> calSupList =
                jdbcNamed.query(SELECT_CALENDAR_SUPS.getSql(schema()), calParams, new CalendarSupRowMapper());
        TreeMap<Version, CalendarSupplemental> supMap = new TreeMap<>();
        for (CalendarSupplemental calSup : calSupList) {
            ImmutableParams calSupParams = calParams.add(of("supVersion", calSup.getVersion().getValue()));
            // Add the supplemental entries
            calSup.setSectionEntries(getCalSupEntries(calSupParams));
            supMap.put(calSup.getVersion(), calSup);
        }
        return supMap;
    }

    /**
     * Retrieves the supplemental entries for a particular supplemental.
     */
    private LinkedListMultimap<CalendarSectionType, CalendarSupplementalEntry> getCalSupEntries(ImmutableParams supParams) {
        List<CalendarSupplementalEntry> entries =
            jdbcNamed.query(SELECT_CALENDAR_SUP_ENTRIES.getSql(schema()), supParams, new CalendarSupEntryRowMapper());
        LinkedListMultimap<CalendarSectionType, CalendarSupplementalEntry> sectionEntries = LinkedListMultimap.create();
        for (CalendarSupplementalEntry entry : entries) {
            sectionEntries.put(entry.getSectionType(), entry);
        }
        return sectionEntries;
    }

    /**
     * Updates the calendar supplementals. Entries belonging to supplementals that have not changed will not
     * be affected.
     */
    private void updateCalSupplementals(Calendar calendar, SobiFragment fragment, ImmutableParams calParams) {
        Map<Version, CalendarSupplemental> existingCalSupMap = getCalSupplementals(calParams);
        // Get the difference between the existing and current supplemental mappings
        MapDifference<Version, CalendarSupplemental> diff =
            Maps.difference(existingCalSupMap, calendar.getSupplementalMap());
        // Delete any supplementals that were not found in the current map or were different.
        Set<Version> deleteSupVersions = Sets.union(diff.entriesDiffering().keySet(), diff.entriesOnlyOnLeft().keySet());
        for (Version supVersion : deleteSupVersions) {
            ImmutableParams calSupParams = calParams.add(new MapSqlParameterSource("supVersion", supVersion.getValue()));
            jdbcNamed.update(DELETE_CALENDAR_SUP.getSql(schema()), calSupParams);
        }
        // Insert any new or differing supplementals
        Set<Version> updateSupVersions = Sets.union(diff.entriesDiffering().keySet(), diff.entriesOnlyOnRight().keySet());
        for (Version supVersion : updateSupVersions) {
            CalendarSupplemental sup = calendar.getSupplemental(supVersion);
            ImmutableParams supParams = ImmutableParams.from(getCalSupplementalParams(sup, fragment));
            jdbcNamed.update(INSERT_CALENDAR_SUP.getSql(schema()), supParams);
            // Insert the calendar entries
            for (CalendarSupplementalEntry entry : sup.getSectionEntries().values()) {
                ImmutableParams entryParams = ImmutableParams.from(getCalSupEntryParams(sup, entry, fragment));
                jdbcNamed.update(INSERT_CALENDAR_SUP_ENTRY.getSql(schema()), entryParams);
            }
        }
    }

    /**
     * Retrieve the active list mappings for a specific calendar.
     */
    private TreeMap<Integer, CalendarActiveList> getActiveListMap(ImmutableParams calParams) {
        List<CalendarActiveList> activeLists =
            jdbcNamed.query(SELECT_CALENDAR_ACTIVE_LISTS.getSql(schema()), calParams, new CalendarActiveListRowMapper());
        TreeMap<Integer, CalendarActiveList> activeListMap = new TreeMap<>();
        for (CalendarActiveList activeList : activeLists) {
            ImmutableParams activeListParams = calParams.add(new MapSqlParameterSource("sequenceNo", activeList.getSequenceNo()));
            activeList.setEntries(getActiveListEntries(activeListParams));
            activeListMap.put(activeList.getSequenceNo(), activeList);
        }
        return activeListMap;
    }

    /**
     * Retrieve entries for a specific active list.
     */
    private List<CalendarActiveListEntry> getActiveListEntries(ImmutableParams activeListParams) {
        return jdbcNamed.query(SELECT_CALENDAR_ACTIVE_LIST_ENTRIES.getSql(schema()), activeListParams,
                               new CalendarActiveListEntryRowMapper());
    }

    /**
     * Updates the calendar active lists. Entries belonging to active lists that have not changed will not
     * be affected.
     */
    private void updateCalActiveLists(Calendar calendar, SobiFragment fragment, ImmutableParams calParams) {
        Map<Integer, CalendarActiveList> existingActiveListMap = getActiveListMap(calParams);
        // Get the difference between the existing and current active list mappings.
        MapDifference<Integer, CalendarActiveList> diff =
                Maps.difference(existingActiveListMap, calendar.getActiveListMap());
        // Delete any active lists that were not found in the current map or were different.
        Set<Integer> deleteActListSeqs = Sets.union(diff.entriesDiffering().keySet(), diff.entriesOnlyOnLeft().keySet());
        for (Integer actListSeq : deleteActListSeqs) {
            ImmutableParams activeListParams = calParams.add(new MapSqlParameterSource("sequenceNo", actListSeq));
            jdbcNamed.update(DELETE_CALENDAR_ACTIVE_LIST.getSql(schema()), activeListParams);
        }
        // Insert any new or differing active lists
        Set<Integer> updateActListSeqs = Sets.union(diff.entriesDiffering().keySet(), diff.entriesOnlyOnRight().keySet());
        for (Integer actListSeq : updateActListSeqs) {
            CalendarActiveList actList = calendar.getActiveList(actListSeq);
            ImmutableParams actListParams = ImmutableParams.from(getCalActiveListParams(actList, fragment));
            jdbcNamed.update(INSERT_CALENDAR_ACTIVE_LIST.getSql(schema()), actListParams);
            // Insert the active list entries
            for (CalendarActiveListEntry entry : actList.getEntries()) {
                ImmutableParams entryParams = ImmutableParams.from(getCalActiveListEntryParams(actList, entry, fragment));
                jdbcNamed.update(INSERT_CALENDAR_ACTIVE_LIST_ENTRY.getSql(schema()), entryParams);
            }
        }
    }

    /** --- Helper Classes --- */

    protected class CalendarRowMapper implements RowMapper<Calendar>
    {
        @Override
        public Calendar mapRow(ResultSet rs, int rowNum) throws SQLException {
            Calendar calendar = new Calendar(new CalendarId(rs.getInt("calendar_no"), rs.getInt("year")));
            setModPubDatesFromResultSet(calendar, rs);
            return calendar;
        }
    }

    protected class CalendarIdRowMapper implements RowMapper<CalendarId>
    {
        @Override
        public CalendarId mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CalendarId(rs.getInt("calendar_no"), rs.getInt("year"));
        }
    }

    protected class CalendarSupRowMapper implements RowMapper<CalendarSupplemental>
    {
        @Override
        public CalendarSupplemental mapRow(ResultSet rs, int rowNum) throws SQLException {
            CalendarId calendarId = new CalendarId(rs.getInt("calendar_no"), rs.getInt("calendar_year"));
            Version version = Version.of(rs.getString("sup_version"));
            LocalDate calDate = getLocalDateFromRs(rs, "calendar_date");
            LocalDateTime releaseDateTime = getLocalDateTimeFromRs(rs, "release_date_time");
            CalendarSupplemental calSup = new CalendarSupplemental(calendarId, version, calDate, releaseDateTime);
            setModPubDatesFromResultSet(calSup, rs);
            return calSup;
        }
    }

    protected class CalendarSupIdRowMapper implements RowMapper<CalendarSupplementalId> {
        @Override
        public CalendarSupplementalId mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CalendarSupplementalId(rs.getInt("calendar_no"), rs.getInt("calendar_year"), Version.of(rs.getString("sup_version")));
        }
    }

    protected class CalendarSupEntryRowMapper implements RowMapper<CalendarSupplementalEntry>
    {
        @Override
        public CalendarSupplementalEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
            CalendarSectionType sectionType = CalendarSectionType.valueOfCode(rs.getInt("section_code"));
            int billCalNo = rs.getInt("bill_calendar_no");
            BillId billId = new BillId(rs.getString("bill_print_no"), rs.getInt("bill_session_year"),
                                       rs.getString("bill_amend_version"));
            BillId subBillId = null;
            if (rs.getString("sub_bill_print_no") != null) {
                subBillId = new BillId(rs.getString("sub_bill_print_no"), rs.getInt("sub_bill_session_year"),
                                       rs.getString("sub_bill_amend_version"));
            }
            boolean high = rs.getBoolean("high");
            return new CalendarSupplementalEntry(billCalNo, sectionType, billId, subBillId, high);
        }
    }

    protected class CalendarActiveListRowMapper implements RowMapper<CalendarActiveList>
    {
        @Override
        public CalendarActiveList mapRow(ResultSet rs, int rowNum) throws SQLException {
            CalendarActiveList activeList = new CalendarActiveList();
            activeList.setSequenceNo(rs.getInt("sequence_no"));
            activeList.setCalendarId(new CalendarId(rs.getInt("calendar_no"), rs.getInt("calendar_year")));
            activeList.setCalDate(getLocalDateFromRs(rs, "calendar_date"));
            activeList.setReleaseDateTime(getLocalDateTimeFromRs(rs, "release_date_time"));
            setModPubDatesFromResultSet(activeList, rs);
            return activeList;
        }
    }

    protected class CalendarActiveListIdRowMapper implements RowMapper<CalendarActiveListId>
    {
        @Override
        public CalendarActiveListId mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CalendarActiveListId(rs.getInt("calendar_no"), rs.getInt("calendar_year"), rs.getInt("sequence_no"));
        }
    }

    protected class CalendarActiveListEntryRowMapper implements RowMapper<CalendarActiveListEntry>
    {
        @Override
        public CalendarActiveListEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
            CalendarActiveListEntry entry = new CalendarActiveListEntry();
            entry.setBillCalNo(rs.getInt("bill_calendar_no"));
            entry.setBillId(new BillId(rs.getString("bill_print_no"), rs.getInt("bill_session_year"),
                                       rs.getString("bill_amend_version")));
            return entry;
        }
    }

    /** --- Param Source Methods --- */

    protected static MapSqlParameterSource getCalendarIdParams(CalendarId calendarId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        addCalendarIdParams(calendarId, params);
        return params;
    }

    protected static MapSqlParameterSource getCalendarActiveListIdParams(CalendarActiveListId calendarActiveListId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        addCalendarIdParams(calendarActiveListId, params);
        params.addValue("sequenceNo", calendarActiveListId.getSequenceNo());
        return params;
    }

    protected static MapSqlParameterSource getCalendarSupplementalIdParams(CalendarSupplementalId calendarSupplementalId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        addCalendarIdParams(calendarSupplementalId, params);
        params.addValue("supVersion", calendarSupplementalId.getVersion().getValue());
        return params;
    }

    protected static MapSqlParameterSource getCalendarParams(Calendar calendar, SobiFragment fragment) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        addCalendarIdParams(calendar.getId(), params);
        addModPubDateParams(calendar.getModifiedDateTime(), calendar.getPublishedDateTime(), params);
        addLastFragmentParam(fragment, params);
        return params;
    }

    protected static MapSqlParameterSource getCalSupplementalParams(CalendarSupplemental sup, SobiFragment fragment) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        addCalendarIdParams(sup.getCalendarId(), params);
        params.addValue("supVersion", sup.getVersion().getValue());
        params.addValue("calendarDate", toDate(sup.getCalDate()));
        params.addValue("releaseDateTime", toDate(sup.getReleaseDateTime()));
        addModPubDateParams(sup.getModifiedDateTime(), sup.getPublishedDateTime(), params);
        addLastFragmentParam(fragment, params);
        return params;
    }

    protected static MapSqlParameterSource getCalSupEntryParams(CalendarSupplemental sup, CalendarSupplementalEntry entry,
                                                                SobiFragment fragment) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        addCalendarIdParams(sup.getCalendarId(), params);
        params.addValue("supVersion", sup.getVersion().getValue());
        params.addValue("sectionCode", entry.getSectionType().getCode());
        params.addValue("billCalNo", entry.getBillCalNo());
        addBillIdParams(entry.getBillId(), params);
        BillId subBillId = entry.getSubBillId();
        params.addValue("subPrintNo", (subBillId != null) ? subBillId.getBasePrintNo() : null);
        params.addValue("subSession", (subBillId != null) ? subBillId.getSession().getYear() : null);
        params.addValue("subAmendVersion", (subBillId != null) ? subBillId.getVersion().getValue() : null);
        params.addValue("high", entry.getBillHigh());
        addLastFragmentParam(fragment, params);
        return params;
    }

    protected static MapSqlParameterSource getCalActiveListParams(CalendarActiveList actList, SobiFragment fragment) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        addCalendarIdParams(actList.getCalendarId(), params);
        params.addValue("sequenceNo", actList.getSequenceNo());
        params.addValue("calendarDate", toDate(actList.getCalDate()));
        params.addValue("releaseDateTime", toDate(actList.getReleaseDateTime()));
        params.addValue("notes", actList.getNotes());
        addModPubDateParams(actList.getModifiedDateTime(), actList.getPublishedDateTime(), params);
        addLastFragmentParam(fragment, params);
        return params;
    }

    protected static MapSqlParameterSource getCalActiveListEntryParams(CalendarActiveList actList,
                                                                       CalendarActiveListEntry entry, SobiFragment fragment) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        addCalendarIdParams(actList.getCalendarId(), params);
        params.addValue("sequenceNo", actList.getSequenceNo());
        params.addValue("billCalendarNo", entry.getBillCalNo());
        addBillIdParams(entry.getBillId(), params);
        addLastFragmentParam(fragment, params);
        return params;
    }

    protected static void addCalendarIdParams(CalendarId calendarId, MapSqlParameterSource params) {
        params.addValue("calendarNo", calendarId.getCalNo());
        params.addValue("year", calendarId.getYear());
    }

    protected static void addBillIdParams(BillId billId, MapSqlParameterSource params) {
        params.addValue("printNo", billId.getBasePrintNo());
        params.addValue("session", billId.getSession().getYear());
        params.addValue("amendVersion", billId.getVersion().getValue());
    }
}