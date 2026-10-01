package br.com.helpdesk.service.mapper;

import br.com.helpdesk.domain.model.Categoria;
import br.com.helpdesk.service.dto.CategoriaResponse;
import org.springframework.stereotype.Component;

@Component
public class CategoriaMapper {

    public CategoriaResponse paraResponse(Categoria categoria) {
        if (categoria == null) {
            return null;
        }
        return new CategoriaResponse(
                categoria.getId(),
                categoria.getNome(),
                categoria.getSlaHoras(),
                categoria.isAtiva());
    }
}
