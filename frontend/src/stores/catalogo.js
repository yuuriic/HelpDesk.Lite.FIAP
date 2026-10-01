import { defineStore } from 'pinia'
import { ref } from 'vue'
import categoriasService from 'src/services/categorias'
import usuariosService from 'src/services/usuarios'

/**
 * Cadastros de apoio: categorias e usuarios. Usado pelos formularios e pelas telas de Admin.
 *
 * Duas listas de categoria, de proposito (FE-04): `categorias` tem so as ativas -- e o que o
 * formulario de chamado e o filtro da listagem oferecem --, `todasCategorias` inclui as
 * inativas para a tela de administracao. Antes era uma lista so, e salvar uma categoria no
 * admin enchia o formulario de chamado de categorias inativas.
 */
export const useCatalogoStore = defineStore('catalogo', () => {
  const categorias = ref([])
  const todasCategorias = ref([])
  const usuarios = ref([])
  const carregandoCategorias = ref(false)
  const carregandoUsuarios = ref(false)

  async function buscarCategorias(incluirInativas) {
    carregandoCategorias.value = true
    try {
      const { data } = await categoriasService.listar(incluirInativas)
      return data
    } finally {
      carregandoCategorias.value = false
    }
  }

  /** So as ativas: formulario de novo chamado e filtro da listagem. */
  async function carregarCategorias() {
    categorias.value = await buscarCategorias(false)
    return categorias.value
  }

  /** Ativas e inativas: tela de administracao (so Admin). */
  async function carregarTodasCategorias() {
    todasCategorias.value = await buscarCategorias(true)
    return todasCategorias.value
  }

  async function salvarCategoria(categoria) {
    const resposta = categoria.id
      ? await categoriasService.atualizar(categoria.id, categoria)
      : await categoriasService.criar(categoria)
    await carregarTodasCategorias()
    return resposta.data
  }

  async function desativarCategoria(id) {
    await categoriasService.desativar(id)
    await carregarTodasCategorias()
  }

  async function carregarUsuarios() {
    carregandoUsuarios.value = true
    try {
      const { data } = await usuariosService.listar()
      usuarios.value = data
      return data
    } finally {
      carregandoUsuarios.value = false
    }
  }

  async function salvarUsuario(usuario) {
    const resposta = usuario.id
      ? await usuariosService.atualizar(usuario.id, usuario)
      : await usuariosService.criar(usuario)
    await carregarUsuarios()
    return resposta.data
  }

  async function desativarUsuario(id) {
    await usuariosService.desativar(id)
    await carregarUsuarios()
  }

  return {
    categorias,
    todasCategorias,
    usuarios,
    carregandoCategorias,
    carregandoUsuarios,
    carregarCategorias,
    carregarTodasCategorias,
    salvarCategoria,
    desativarCategoria,
    carregarUsuarios,
    salvarUsuario,
    desativarUsuario
  }
})
