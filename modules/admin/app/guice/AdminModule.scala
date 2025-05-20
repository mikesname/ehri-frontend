package guice

import com.google.inject.AbstractModule
import eu.ehri.project.xml.{BaseXXQueryXmlTransformer, SaxonXsltXmlTransformer, XQueryXmlTransformer, XsltXmlTransformer}
import services.harvesting._
import services.ingest.{CoreferenceService, EadValidator, IngestService, RelaxNGEadValidator, SqlCoreferenceService, WsIngestService}

import javax.inject.Provider

class AdminModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[IngestService]).to(classOf[WsIngestService])
    bind(classOf[EadValidator]).to(classOf[RelaxNGEadValidator])
    bind(classOf[OaiPmhClient]).to(classOf[WsOaiPmhClient])
    bind(classOf[ResourceSyncClient]).to(classOf[WsResourceSyncClient])
    bind(classOf[HarvestEventService]).to(classOf[SqlHarvestEventService])
    bind(classOf[XsltXmlTransformer]).to(classOf[SaxonXsltXmlTransformer])
    bind(classOf[CoreferenceService]).to(classOf[SqlCoreferenceService])
    bind(classOf[XQueryXmlTransformer]).toProvider(new Provider[XQueryXmlTransformer] {
      override def get(): XQueryXmlTransformer = BaseXXQueryXmlTransformer()
    })
  }
}
