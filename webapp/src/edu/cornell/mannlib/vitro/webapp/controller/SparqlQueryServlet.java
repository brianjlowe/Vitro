/* $This file is distributed under the terms of the license in /doc/license.txt$ */

package edu.cornell.mannlib.vitro.webapp.controller;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.query.QuerySolution;
import com.hp.hpl.jena.query.ResultSet;
import com.hp.hpl.jena.query.ResultSetFactory;
import com.hp.hpl.jena.query.ResultSetFormatter;
import com.hp.hpl.jena.rdf.model.Literal;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.sparql.resultset.ResultSetFormat;
import com.hp.hpl.jena.vocabulary.XSD;

import edu.cornell.mannlib.vedit.controller.BaseEditController;
import edu.cornell.mannlib.vitro.webapp.auth.permissions.SimplePermission;
import edu.cornell.mannlib.vitro.webapp.beans.Ontology;
import edu.cornell.mannlib.vitro.webapp.dao.OntologyDao;
import edu.cornell.mannlib.vitro.webapp.rdfservice.RDFService;
import edu.cornell.mannlib.vitro.webapp.rdfservice.RDFService.ModelSerializationFormat;
import edu.cornell.mannlib.vitro.webapp.rdfservice.RDFService.ResultFormat;
import edu.cornell.mannlib.vitro.webapp.rdfservice.RDFServiceException;
import edu.cornell.mannlib.vitro.webapp.rdfservice.impl.RDFServiceUtils;
import edu.cornell.mannlib.vitro.webapp.utils.SparqlQueryUtils;


/**
 * Services a sparql query.  This will return a simple error message and a 501 if
 * there is no Model.
 *
 * @author bdc34
 *
 */
public class SparqlQueryServlet extends BaseEditController {
    private static final Log log = LogFactory.getLog(SparqlQueryServlet.class.getName());

    private final static boolean CONVERT = true;
        
    /**
     * format configurations for SELECT queries.
     */
    protected static HashMap<String,RSFormatConfig> rsFormats = new HashMap<String,RSFormatConfig>();

    private static RSFormatConfig[] rsfs = {
        new RSFormatConfig( "RS_XML", !CONVERT, ResultFormat.XML, null, "text/xml"),
        new RSFormatConfig( "RS_TEXT", !CONVERT, ResultFormat.TEXT, null, "text/plain"),
        new RSFormatConfig( "vitro:csv", !CONVERT, ResultFormat.CSV, null, "text/csv"),
        new RSFormatConfig( "RS_JSON", !CONVERT, ResultFormat.JSON, null, "application/javascript") };
    
    /**
     * format configurations for CONSTRUCT/DESCRIBE queries.
     */
    protected static HashMap<String,ModelFormatConfig> modelFormats = 
        new HashMap<String,ModelFormatConfig>();

    private static ModelFormatConfig[] fmts = {
        new ModelFormatConfig("RDF/XML", !CONVERT, ModelSerializationFormat.RDFXML, null, "application/rdf+xml" ),
        new ModelFormatConfig("RDF/XML-ABBREV", CONVERT, ModelSerializationFormat.N3, "RDF/XML-ABBREV", "application/rdf+xml" ),
        new ModelFormatConfig("N3", !CONVERT, ModelSerializationFormat.N3, null, "text/n3" ),
        new ModelFormatConfig("N-TRIPLE", !CONVERT, ModelSerializationFormat.NTRIPLE, null, "text/plain" ),
        new ModelFormatConfig("TTL", CONVERT, ModelSerializationFormat.N3, "TTL", "application/x-turtle" ),
        new ModelFormatConfig("JSON-LD", CONVERT, ModelSerializationFormat.N3, null, "application/x-turtle" ) };

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
    throws ServletException, IOException
    {
        this.doGet(request,response);
    }
    
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
    throws ServletException, IOException
    {    	    	   	
		if (!isAuthorizedToDisplayPage(request, response,
				SimplePermission.USE_SPARQL_QUERY_PAGE.ACTIONS)) {
    		return;
    	}

        VitroRequest vreq = new VitroRequest(request);

        Model model = vreq.getJenaOntModel(); 
        if( model == null ){
            doNoModelInContext(response);
            return;
        }

        String queryParam = vreq.getParameter("query");
        log.debug("queryParam was : " + queryParam);

        String resultFormatParam = vreq.getParameter("resultFormat");
        log.debug("resultFormat was: " + resultFormatParam);
        
        String rdfResultFormatParam = vreq.getParameter("rdfResultFormat");
        if (rdfResultFormatParam == null) {
        	rdfResultFormatParam = "RDF/XML-ABBREV";
        }
        log.debug("rdfResultFormat was: " + rdfResultFormatParam);

        if( queryParam == null || "".equals(queryParam) ||
            resultFormatParam == null || "".equals(resultFormatParam) ||
            !rsFormats.containsKey( resultFormatParam ) || 
            rdfResultFormatParam == null || "".equals(rdfResultFormatParam) ||
            !modelFormats.containsKey( rdfResultFormatParam ) ) {
            doHelp(request,response);
            return;
        }

        executeQuery(response, resultFormatParam, rdfResultFormatParam, 
                queryParam, vreq.getUnfilteredRDFService()); 
        return;
    }
    
    private void executeQuery(HttpServletResponse response, 
                              String resultFormatParam, 
                              String rdfResultFormatParam, 
                              String queryParam, 
                              RDFService rdfService ) throws IOException {        
    	/* BJL23 2008-11-06
    	 * modified to support CSV output.
    	 * Unfortunately, ARQ doesn't make it easy to
    	 * do this by implementing a new ResultSetFormat, because 
    	 * ResultSetFormatter is hardwired with expected values.
    	 * This slightly ugly approach will have to do for now. 
    	 */
//        if ( !("vitro:csv").equals(resultFormatParam) ) {
//        	rsf = selectFormatSymbols.get(resultFormatParam);
//        }                       
//        String mimeType = rdfFormatToMimeType.get(resultFormatParam);
        
        try{
            Query query = SparqlQueryUtils.create(queryParam);
            if( query.isSelectType() ){
                doSelectQuery( queryParam, rdfService, resultFormatParam, response);
            } else if(query.isAskType()){
                // Irrespective of the ResultFormatParam, 
                // this always prints a boolean to the default OutputStream.
                String result = (rdfService.sparqlAskQuery(queryParam) == true) 
                    ? "true" 
                    : "false";
                PrintWriter p = response.getWriter();
                p.write(result);
                return;                
            } else {                
                doModelResultQuery( query, rdfService, rdfResultFormatParam, response);
            }
        } catch (RDFServiceException e) {
                throw new RuntimeException(e);
        }
    }

    /**
     * Execute the query and send the result to out. Attempt to
     * send the RDFService the same format as the rdfResultFormatParam
     * so that the results from the RDFService can be directly piped to the client.
     * @param rdfService 
     * @throws IOException 
     * @throws RDFServiceException 
     */
    private void doSelectQuery( String queryParam,
                                RDFService rdfService, String resultFormatParam,
                                HttpServletResponse response) throws IOException, RDFServiceException{                
        RSFormatConfig config = rsFormats.get( resultFormatParam );
                
        if( ! config.converstionFromWireFormat ){
            response.setContentType( config.responseMimeType );
            InputStream results = rdfService.sparqlSelectQuery(queryParam, config.wireFormat );                        
            pipe( results, response.getOutputStream() );
        }else{                        
            //always use JSON when conversion is needed.
            InputStream results = rdfService.sparqlSelectQuery(queryParam, ResultFormat.JSON );
            
            response.setContentType( config.responseMimeType );
                        
            ResultSet rs = ResultSetFactory.fromJSON( results );            
            OutputStream out = response.getOutputStream();
            ResultSetFormatter.output(out, rs, config.jenaResponseFormat);
            
            // } else {
            //     Writer out = response.getWriter();
            //     toCsv(out, results);
            //}
        }
    }

    /**
     * Execute the query and send the result to out. Attempt to
     * send the RDFService the same format as the rdfResultFormatParam
     * so that the results from the RDFService can be directly piped to the client.
     * @param rdfService 
     * @throws IOException 
     * @throws RDFServiceException 
     */
    private void doModelResultQuery( Query query, 
                                      RDFService rdfService, String rdfResultFormatParam, 
                                      HttpServletResponse response) throws IOException, RDFServiceException{

        //config drives what formats and conversions to use 
        ModelFormatConfig config = modelFormats.get( rdfResultFormatParam );

        InputStream rawResult = null;        
        if( query.isConstructType() ){                    
            rawResult= rdfService.sparqlConstructQuery( query.toString(), config.wireFormat );
        }else if ( query.isDescribeType() ){
            rawResult = rdfService.sparqlDescribeQuery( query.toString(), config.wireFormat );
        }

        response.setContentType(  config.responseMimeType );
        OutputStream out = response.getOutputStream();

        if( config.converstionFromWireFormat ){
            Model resultModel = RDFServiceUtils.parseModel( rawResult, config.wireFormat );
            resultModel.write(out, config.jenaResponseFormat );
        }else{
            pipe( rawResult, out );
        }
    }

    private void pipe( InputStream in, OutputStream out) throws IOException{
        int size;
        byte[] buffer = new byte[4096];
        while( (size = in.read(buffer)) > -1 ) {
            out.write(buffer,0,size);
        }        
    }

    private void doNoModelInContext(HttpServletResponse res){
        try {
            res.setStatus(HttpServletResponse.SC_NOT_IMPLEMENTED);
            ServletOutputStream sos = res.getOutputStream();
            sos.println("<html><body>this service is not supporeted by the current " +
                    "webapp configuration. A jena model is required in the servlet context.</body></html>" );
        } catch (IOException e) {
            log.error("Could not write to ServletOutputStream");
        }
    }

    private void toCsv(Writer out, ResultSet results) {
    	// The Skife library wouldn't quote and escape the normal way, 
    	// so I'm trying it manually.
        List<String> varNames = results.getResultVars();
        int width = varNames.size();
    	while (results.hasNext()) {
    		QuerySolution solution = (QuerySolution) results.next();
    		String[] valueArray = new String[width];
    		Iterator<String> varNameIt = varNames.iterator();
    		int index = 0;
    		while (varNameIt.hasNext()) {
    			String varName = varNameIt.next();
    			String value = null;
    			try {
    				Literal lit = solution.getLiteral(varName);
    				if (lit != null) { 
    					value = lit.getLexicalForm();
    					if (XSD.anyURI.getURI().equals(lit.getDatatypeURI())) {
    						value = URLDecoder.decode(value, "UTF-8");
    					}
    				}
    			} catch (Exception e) {
    				try {
    					Resource res = solution.getResource(varName);
    					if (res != null) {
        					if (res.isAnon()) {
        						value = res.getId().toString();
        					} else {
        						value = res.getURI();
        					}
    					}
	    			} catch (Exception f) {}
    			}
    			valueArray[index] = value;
                index++;
    		}

			StringBuffer rowBuff = new StringBuffer();
			for (int i = 0; i < valueArray.length; i++) {
    			String value = valueArray[i];
    			if (value != null) {
    			    value.replaceAll("\"", "\\\"");
    			    rowBuff.append("\"").append(value).append("\"");
    			}
    			if (i + 1 < width) {
    				rowBuff.append(",");
    			}
    		}
    		rowBuff.append("\n");
    		try {
    			out.write(rowBuff.toString());
    		} catch (IOException ioe) {
    			log.error(ioe);
    		}
    	}
    	try {
    		out.flush();
    	} catch (IOException ioe) {
    		log.error(ioe);
    	}
    }
    
    private void doHelp(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
            VitroRequest vreq = new VitroRequest(req);
            
            OntologyDao daoObj = vreq.getUnfilteredWebappDaoFactory().getOntologyDao();
            List<Ontology> ontologiesObj = daoObj.getAllOntologies();
            ArrayList<String> prefixList = new ArrayList<String>();
            
            if(ontologiesObj !=null && ontologiesObj.size()>0){
            	for(Ontology ont: ontologiesObj) {
            		prefixList.add(ont.getPrefix() == null ? "(not yet specified)" : ont.getPrefix());
            		prefixList.add(ont.getURI() == null ? "" : ont.getURI());
            	}
            }
            else{
            	prefixList.add("<strong>" + "No Ontologies added" + "</strong>");
            	prefixList.add("<strong>" + "Load Ontologies" + "</strong>");
            }
            
            req.setAttribute("prefixList", prefixList);
            
            // nac26: 2009-09-25 - this was causing problems in safari on localhost installations because the href did not include the context.  The edit.css is not being used here anyway (or anywhere else for that matter)
            // req.setAttribute("css", "<link rel=\"stylesheet\" type=\"text/css\" href=\""+portal.getThemeDir()+"css/edit.css\"/>");
            req.setAttribute("title","SPARQL Query");
            req.setAttribute("bodyJsp", "/admin/sparqlquery/sparqlForm.jsp");
            
            RequestDispatcher rd = req.getRequestDispatcher("/"+Controllers.BASIC_JSP);
            rd.forward(req,res);
    }


protected static class ModelFormatConfig{
    String valueFromForm;
    boolean converstionFromWireFormat;
    RDFService.ModelSerializationFormat wireFormat;
    String jenaResponseFormat;
    String responseMimeType;
    public ModelFormatConfig( String valueFromForm,
                              boolean converstionFromWireFormat, 
                              RDFService.ModelSerializationFormat wireFormat, 
                              String jenaResponseFormat, 
                              String responseMimeType){
        this.valueFromForm = valueFromForm;
        this.converstionFromWireFormat = converstionFromWireFormat;
        this.wireFormat = wireFormat;
        this.jenaResponseFormat = jenaResponseFormat;
        this.responseMimeType = responseMimeType;
    }
}

    protected static class RSFormatConfig{
        String valueFromForm;
        boolean converstionFromWireFormat;
        ResultFormat wireFormat;
        ResultSetFormat jenaResponseFormat;
        String responseMimeType;
        public RSFormatConfig( String valueFromForm,
                               boolean converstionFromWireFormat,
                               ResultFormat wireFormat,
                               ResultSetFormat jenaResponseFormat,
                               String responseMimeType ){
            this.valueFromForm = valueFromForm;
            this.converstionFromWireFormat = converstionFromWireFormat;
            this.wireFormat = wireFormat;
            this.jenaResponseFormat = jenaResponseFormat;
            this.responseMimeType = responseMimeType;
        }    
    }

    static{
        /* move the lists of configs into maps for easy lookup */
        for( RSFormatConfig rsfc : rsfs ){
            rsFormats.put( rsfc.valueFromForm, rsfc );
        }
        for( ModelFormatConfig mfc : fmts ){
            modelFormats.put( mfc.valueFromForm, mfc);
        }        
    }

}
