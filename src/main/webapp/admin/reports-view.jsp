<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" import="java.text.SimpleDateFormat, java.util.ArrayList,gov.nysenate.openleg.model.ReportObservation, gov.nysenate.openleg.model.ReportError, gov.nysenate.openleg.model.Report,gov.nysenate.openleg.util.JSPHelper"%>
<%!
public String getStatus(Report report, ReportObservation obs) {
    for (ReportError error : report.getNewErrors()) {
        if (error.getField().equals(obs.getField()) && error.getOid().equals(obs.getOid())) {
            return "NEW";
        }
    }
    return "OLD";
}
%>
<%
    @SuppressWarnings("unchecked")
	Report report= (Report)request.getAttribute("report");
	String title = "Report for "+new SimpleDateFormat("yyyy-MM-dd").format(report.getTime())+" - OpenLeg Admin";
%>
<jsp:include page="/admin/header.jsp">
    <jsp:param value="<%=title%>" name="title"/>
</jsp:include>
<script type="text/javascript" src="<%=JSPHelper.getLink(request, "/static/js/diff.js")%>"></script>
<script type="text/javascript">
	$(document).ready(function(){
		
		var dataTable = $("#errors").dataTable({
			"bLengthChange": false,
			"bPaginate": false,
			"bFilter": true,
			"bSort": true,
            "sDom": '<"hidden-controls"f>lrtip',
            "aaSorting": [[2, "asc"],[0,"asc"]],
            "aoColumnDefs": [
                {"aTargets":[0,1], "sWidth":"10%" },
                {"aTargets":[2,4], "sWidth":"5%" }
            ]
		});
		
		$("#report-filter input").keyup(function() {
	        dataTable.fnFilter($(this).val());
	    });
		
        $(".error-detail").each(function() {
            var details = $(this);
            var diffCell = $(".diff", details);
            var jsonCell = $(".json", details);
            var lbdcCell = $(".lbdc", details);
            
            
            var errorId = details.attr("id").substring(7);
            var summary = $("#summary"+errorId);
            var oidCell = $(".oid-cell", summary);
            var snippetCell = $(".snippet-cell", summary);
            var detailsLink = $(".details-link", summary);
            var statusCell = $(".status-cell", summary);
            var fieldCell = $(".field-cell", summary);
            
            details.dialog({
            	"title": "Details for "+statusCell.html()+" "+oidCell.attr("oid")+" "+fieldCell.html()+" Error",
                "autoOpen": false,
                "modal": true,
                "width": "auto",
                "height": "auto",
            });
            
            $(".tabbed-content", details).tabs();
            
            detailsLink.click(function() {
            	details.dialog("open");
            });
            
            if (jsonCell.html() == "") {
                diffCell.html(lbdcCell.html());
                snippetCell.html("FIELD COMPLETELY MISSING");
            }
            else {
                diffCell.html(diffString(jsonCell.html(),lbdcCell.html()));
                snippetCell.html("<div>"+diffCell.html()+"</div>");
            }
        });
	});
</script>
<style>
	#errors {
	    width:100%;
	}
	
	#errorDetails {
	   display:None;
	}
	
	.details-link {
	   cursor: pointer;
	   color: blue;
	   text-decoration: underline;
	}
	.hidden-controls {
	    display: none;
	}

	#errors_paginate {
	    margin:10px 15px 0px 0px; 
	}
	
	#errors_info {
	    margin:10px 0px 0px 15px;
	}

	del {
	   color: red;
	}
	
	ins {
	   color: green;
	}

	td > div { height:40px; width: 100%;  overflow:hidden; }
	
	.filter {
	   float: right;
	}
	
	.section-title {
	   float:left;
	}
	
	#report-filter {
	    float: right;
	    padding: 10px;
	}
</style>
<div id="section-header">
    <label id="report-filter">Filter: <input type="text"/></label>
    <div id="section-title">
        <%=new SimpleDateFormat("yyyy-MM-dd").format(report.getTime())%> Report
    </div>
	<div style="clear:both;"></div>
</div>
<div class="container">
    <table id="errors">
        <thead>
            <tr>
				<th>Bill Id</th>
				<th>Error Type</th>
				<th>Status</th>
				<th>Snippet</th>
				<th>Details</th>
			</tr>
        </thead>
        <tbody>
        <% for(ReportObservation obs:report.getObservations()) { %>
            <tr id="summary<%=obs.getId()%>">
			    <td class="oid-cell" oid="<%=obs.getOid()%>"><a href="#<%=obs.getOid()%>"><%=obs.getOid()%></a></td>
			    <td class="field-cell"><%=obs.getField().toUpperCase()%></td>
			    <td class="status-cell"><%=getStatus(report,obs)%></td>
			    <td class="snippet-cell"></td>
			    <td class="details-link">Details</td>
			</tr>
        <% } %>
        </tbody>  
    </table>
    <div id="errorDetails">
    <% for (ReportObservation obs:report.getObservations()) { %>
        <div id="details<%=obs.getId()%>" class="error-detail">
	        <div class="tabbed-content">
	            <ul>
	                <li><a href="#details<%=obs.getId()%>-diff"><span>DIFF</span></a></li>
	                <li><a href="#details<%=obs.getId()%>-lbdc"><span>LBDC</span></a></li>
	                <li><a href="#details<%=obs.getId()%>-json"><span>JSON</span></a></li>
	            </ul>
		        <div id="details<%=obs.getId()%>-diff">
	                <pre class="diff"></pre>
		        </div>
		        <div id="details<%=obs.getId()%>-lbdc">
	                <pre class="lbdc"><%=obs.getActualValue()%></pre><br>
		        </div>
		        <div id="details<%=obs.getId()%>-json"> 
	                <pre class="json"><%=obs.getObservedValue()%></pre>
		        </div>
	        </div>
        </div>
    <% } %>
    </div>
</div>
<jsp:include page="/admin/footer.jsp" />